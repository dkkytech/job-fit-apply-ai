/**
 * background.js  (MV3 service worker, ES module)
 *
 * Responsibilities:
 *  - Register context menu item
 *  - Receive trigger from context menu OR popup
 *  - Coordinate content script extraction
 *  - POST JD to the Bridge API
 *  - Poll Bridge API for completion
 *  - Write job state to chrome.storage.local (keyed by tabId)
 *  - Fire a notification when the job is done
 */

import { BRIDGE_API_URL, POLL_INTERVAL_MS, POLL_TIMEOUT_MS, MIN_JD_CHARS, STATUS } from './config.js';

// ── Setup ─────────────────────────────────────────────────────────────────────

chrome.runtime.onInstalled.addListener(() => {
  // Remove existing menu item first to prevent duplicate ID errors
  // that can occur when service worker restarts after installation
  chrome.contextMenus.remove('oc-send-jd', () => {
    chrome.contextMenus.create({
      id:       'oc-send-jd',
      title:    '🦞 Send to OpenClaw → Generate Resume',
      contexts: ['page', 'selection'],
    });
  });
});

// ── Event listeners ───────────────────────────────────────────────────────────

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === 'oc-send-jd') {
    await triggerExtraction(tab);
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  switch (message.type) {

    // Popup clicked "Generate"
    case 'POPUP_TRIGGER': {
      chrome.tabs.query({ active: true, currentWindow: true }, async ([tab]) => {
        if (!tab) { sendResponse({ ok: false, error: 'No active tab' }); return; }
        await triggerExtraction(tab);
        sendResponse({ ok: true });
      });
      return true;
    }

    // Popup wants current job state for the active tab
    case 'GET_JOB_STATUS': {
      getJobState(message.tabId).then(state => sendResponse(state));
      return true;
    }

    // Popup wants to clear/reset the current job
    case 'CLEAR_JOB': {
      clearJobState(message.tabId).then(() => sendResponse({ ok: true }));
      return true;
    }
  }
});

// Tab navigated away — clear stale state
chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === 'loading') {
    clearJobState(tabId);
  }
});

// ── Core pipeline ─────────────────────────────────────────────────────────────

async function triggerExtraction(tab) {
  await setJobState(tab.id, { status: STATUS.EXTRACTING, pageTitle: tab.title, url: tab.url });

  // 1. Ensure content script is alive (may need injection on restricted pages)
  try {
    await chrome.tabs.sendMessage(tab.id, { type: 'PING' });
  } catch {
    // Content script not yet running — inject it
    try {
      await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        files: ['extractors/jd_extractor.js', 'content_script.js'],
      });
    } catch (injErr) {
      await setJobState(tab.id, {
        status: STATUS.ERROR,
        error:  `Cannot inject into this page: ${injErr.message}`,
      });
      return;
    }
  }

  // 2. Extract JD
  let jd;
  try {
    const response = await chrome.tabs.sendMessage(tab.id, { type: 'EXTRACT_JD' });
    if (!response?.success) {
      await setJobState(tab.id, { status: STATUS.ERROR, error: response?.error || 'Extraction failed' });
      return;
    }
    jd = response.jd;

    if (!jd.body || jd.body.length < MIN_JD_CHARS) {
      await setJobState(tab.id, {
        status: STATUS.ERROR,
        error:  `Page doesn't look like a job listing (only ${jd.body?.length ?? 0} chars extracted).`,
      });
      return;
    }
  } catch (err) {
    await setJobState(tab.id, { status: STATUS.ERROR, error: err.message });
    return;
  }

  // 3. Submit to Bridge API
  await setJobState(tab.id, {
    status:  STATUS.SUBMITTING,
    jdTitle: jd.title,
    company: jd.company,
    site:    jd.site,
  });

  let jobId;
  try {
    jobId = await submitToBridge(jd);
  } catch (err) {
    await setJobState(tab.id, {
      status: STATUS.ERROR,
      error:  `Bridge API unreachable: ${err.message}. Is the server running on the M1 Max?`,
    });
    return;
  }

  // 4. Poll for completion
  await setJobState(tab.id, {
    status:  STATUS.PROCESSING,
    jobId,
    jdTitle: jd.title,
    company: jd.company,
    site:    jd.site,
    progressMessage: 'OpenClaw is generating your documents…',
  });

  pollForCompletion(tab.id, jobId, jd);
}

// ── Bridge API calls ──────────────────────────────────────────────────────────

async function submitToBridge(jd) {
  const res = await fetch(`${BRIDGE_API_URL}/api/jobs`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({
      jd_text:    jd.body,
      role_title: jd.title,
      company:    jd.company,
      location:   jd.location,
      job_url:    jd.url,
      site:       jd.site,
    }),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`HTTP ${res.status}: ${text}`);
  }

  const data = await res.json();
  if (!data.job_id) throw new Error('Bridge API returned no job_id');
  return data.job_id;
}

async function pollForCompletion(tabId, jobId, jd) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;

  const tick = async () => {
    if (Date.now() > deadline) {
      await setJobState(tabId, {
        status: STATUS.ERROR,
        error:  'Timed out after 5 minutes waiting for OpenClaw.',
      });
      return;
    }

    let data;
    try {
      const res = await fetch(`${BRIDGE_API_URL}/api/jobs/${jobId}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      data = await res.json();
    } catch {
      // Network blip — just retry
      setTimeout(tick, POLL_INTERVAL_MS);
      return;
    }

    if (data.status === 'complete') {
      await setJobState(tabId, {
        status:    STATUS.COMPLETE,
        jobId,
        jdTitle:   data.title  || jd.title,
        company:   data.company|| jd.company,
        artifacts: data.artifacts, // { resume_pdf, cover_letter_txt }
      });

      chrome.notifications.create(`oc-done-${jobId}`, {
        type:    'basic',
        iconUrl: 'icons/icon48.png',
        title:   '🦞 OpenClaw: Documents Ready',
        message: `Resume & cover letter generated for ${data.title || jd.title || 'this role'}`,
      });
      return;
    }

    if (data.status === 'error') {
      await setJobState(tabId, {
        status: STATUS.ERROR,
        error:  data.error || 'OpenClaw returned an error.',
      });
      return;
    }

    // Still processing — update progress message if available
    const current = await getJobState(tabId) || {};
    await setJobState(tabId, {
      ...current,
      progressMessage: data.progress_message || current.progressMessage,
    });

    setTimeout(tick, POLL_INTERVAL_MS);
  };

  setTimeout(tick, POLL_INTERVAL_MS);
}

// ── Storage helpers ───────────────────────────────────────────────────────────

const KEY = (tabId) => `job_${tabId}`;

async function setJobState(tabId, patch) {
  const existing = await getJobState(tabId) || {};
  const next = { ...existing, ...patch, updatedAt: Date.now() };
  return new Promise(resolve =>
    chrome.storage.local.set({ [KEY(tabId)]: next }, resolve)
  );
}

async function getJobState(tabId) {
  return new Promise(resolve =>
    chrome.storage.local.get(KEY(tabId), result =>
      resolve(result[KEY(tabId)] || null)
    )
  );
}

async function clearJobState(tabId) {
  return new Promise(resolve =>
    chrome.storage.local.remove(KEY(tabId), resolve)
  );
}
