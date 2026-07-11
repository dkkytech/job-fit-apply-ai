/**
 * popup.js
 *
 * Runs in the popup window. On load:
 *  1. Gets the active tab ID
 *  2. Reads job state from background via message
 *  3. Renders the current state
 *  4. Starts polling for updates (storage events don't cross to popup, so we poll)
 *
 * All mutation goes through the background service worker.
 */

// ── DOM refs ──────────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);

const siteBadge    = $('site-badge');
const jobCard      = $('job-card');
const jobTitle     = $('job-title');
const jobCompany   = $('job-company');
const progressMsg  = $('progress-msg');

const ctaIdle      = $('cta-idle');
const btnGenerate  = $('btn-generate');

const errorBlock   = $('error-block');
const errorMsg     = $('error-msg');
const btnRetry     = $('btn-retry');

const doneBlock    = $('done-block');
const artifactLinks= $('artifact-links');
const btnReset     = $('btn-reset');

const steps = {
  extracting: $('step-extract'),
  submitting: $('step-submit'),
  processing: $('step-process'),
  complete:   $('step-complete'),
};

const connectors = document.querySelectorAll('.pipe-connector');

// ── State ─────────────────────────────────────────────────────────────────────

let activeTabId = null;
let pollTimer   = null;

// ── Boot ──────────────────────────────────────────────────────────────────────

(async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) return;
  activeTabId = tab.id;

  // Show the site in the badge
  try {
    const host = new URL(tab.url).hostname.replace(/^www\./, '');
    siteBadge.textContent = host.length > 28 ? host.slice(0, 26) + '…' : host;
  } catch { siteBadge.textContent = '—'; }

  await refreshState();
  startPolling();
})();

// ── Polling ───────────────────────────────────────────────────────────────────

function startPolling() {
  pollTimer = setInterval(refreshState, 1500);
}

async function refreshState() {
  if (!activeTabId) return;
  const state = await getJobState(activeTabId);
  render(state);
}

// ── Render ────────────────────────────────────────────────────────────────────

function render(state) {
  const status = state?.status || 'idle';

  // Job metadata
  if (state?.jdTitle || state?.company) {
    jobCard.style.display = '';
    jobTitle.textContent  = state.jdTitle  || 'Untitled Role';
    jobCompany.textContent= state.company  || '—';
  } else {
    jobCard.style.display = 'none';
  }

  // Progress message
  progressMsg.textContent = state?.progressMessage || '';

  // Pipeline steps
  updatePipeline(status);

  // Block visibility
  ctaIdle.hidden   = !['idle', 'error'].includes(status);
  errorBlock.hidden = status !== 'error';
  doneBlock.hidden  = status !== 'complete';

  if (status === 'error') {
    errorMsg.textContent = state.error || 'Unknown error.';
  }

  if (status === 'complete' && state?.artifacts) {
    renderArtifacts(state.artifacts, state.jobId);
    clearInterval(pollTimer); // done, stop polling
  }

  // Button state
  btnGenerate.disabled = ['extracting','submitting','processing'].includes(status);
}

function updatePipeline(status) {
  const ORDER = ['extracting', 'submitting', 'processing', 'complete'];
  const idx   = ORDER.indexOf(status);

  ORDER.forEach((step, i) => {
    const el = steps[step];
    if (!el) return;
    el.classList.remove('active', 'done', 'error');

    if (status === 'error' && i <= Math.max(idx, 0)) {
      // Mark the last reached step as error
      if (i === Math.max(idx, 0)) el.classList.add('error');
      else el.classList.add('done');
    } else if (i < idx) {
      el.classList.add('done');
    } else if (i === idx) {
      el.classList.add('active');
    }
  });

  // Connectors (there are 3, between steps 0-1, 1-2, 2-3)
  connectors.forEach((c, i) => {
    c.classList.remove('done', 'active');
    if (i < idx - 1) c.classList.add('done');
    else if (i === idx - 1) c.classList.add('active');
  });
}

function renderArtifacts(artifacts, jobId) {
  artifactLinks.innerHTML = '';

  const items = [
    { key: 'resume_pdf',       label: 'Resume',       icon: '📄', type: 'PDF' },
    { key: 'cover_letter_txt', label: 'Cover Letter', icon: '✉️',  type: 'TXT' },
  ];

  for (const { key, label, icon, type } of items) {
    const url = artifacts[key];
    if (!url) continue;

    const a = document.createElement('a');
    a.href      = url;
    a.target    = '_blank';
    a.rel       = 'noopener noreferrer';
    a.className = 'artifact-link';
    a.innerHTML = `
      <span class="artifact-icon">${icon}</span>
      <span>${label}</span>
      <span class="artifact-type">${type}</span>
    `;
    artifactLinks.appendChild(a);
  }
}

// ── Button handlers ───────────────────────────────────────────────────────────

btnGenerate.addEventListener('click', async () => {
  btnGenerate.disabled = true;
  await chrome.runtime.sendMessage({ type: 'POPUP_TRIGGER' });
  await refreshState();
});

btnRetry.addEventListener('click', async () => {
  await clearJobState(activeTabId);
  await refreshState();
});

btnReset.addEventListener('click', async () => {
  await clearJobState(activeTabId);
  clearInterval(pollTimer);
  startPolling();
  await refreshState();
});

// ── Messaging helpers ─────────────────────────────────────────────────────────

function getJobState(tabId) {
  return new Promise(resolve =>
    chrome.runtime.sendMessage({ type: 'GET_JOB_STATUS', tabId }, resolve)
  );
}

function clearJobState(tabId) {
  return new Promise(resolve =>
    chrome.runtime.sendMessage({ type: 'CLEAR_JOB', tabId }, resolve)
  );
}
