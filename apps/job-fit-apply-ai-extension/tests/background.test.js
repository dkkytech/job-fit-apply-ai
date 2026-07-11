/**
 * tests/background.test.js
 *
 * Unit tests for background.js — the MV3 service worker.
 * Each describe block reloads the module to get a fresh listener registration.
 */

// ── Helpers ────────────────────────────────────────────────────────────────────

function getMessageListener() {
  const calls = global.chrome.runtime.onMessage.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

function getContextMenuListener() {
  const calls = global.chrome.contextMenus.onClicked.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

function getInstallListener() {
  const calls = global.chrome.runtime.onInstalled.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

// Storage keys mirror the KEY() helper in background.js
const stateKey = (tabId) => `job_${tabId}`;

// ── Module loading ─────────────────────────────────────────────────────────────

beforeEach(() => {
  jest.resetModules();
  // Clear all mock call histories and the in-memory storage
  jest.clearAllMocks();
  global._storageMock._reset();
});

function loadBackground() {
  // background.js uses ES module syntax but Jest runs CJS — jest-environment-jsdom
  // doesn't transform it, so we use a dynamic require after jest.resetModules().
  return require('../background.js');
}

// ── onInstalled ────────────────────────────────────────────────────────────────

describe('onInstalled handler', () => {
  it('removes then recreates the context menu item', () => {
    loadBackground();

    const handler = getInstallListener();
    handler();

    expect(chrome.contextMenus.remove).toHaveBeenCalledWith('oc-send-jd', expect.any(Function));

    // Simulate the remove callback to trigger create
    const removeCallback = chrome.contextMenus.remove.mock.calls[0][1];
    removeCallback();

    expect(chrome.contextMenus.create).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'oc-send-jd' })
    );
  });
});

// ── Message listener ────────────────────────────────────────────────────────────

describe('runtime.onMessage', () => {
  describe('POPUP_TRIGGER', () => {
    it('queries the active tab and calls triggerExtraction', async () => {
      loadBackground();
      const listener = getMessageListener();

      const tab = { id: 42, title: 'Test Job', url: 'https://example.com/job' };
      chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));
      // Make sendMessage resolve (PING succeeds)
      chrome.tabs.sendMessage.mockResolvedValue({ alive: true });
      // Make EXTRACT_JD return a short body so we hit the MIN_JD_CHARS guard
      chrome.tabs.sendMessage.mockResolvedValueOnce({ alive: true });
      chrome.tabs.sendMessage.mockResolvedValueOnce({
        success: false,
        error: 'Extractor not loaded',
      });

      const sendResponse = jest.fn();
      const returnVal = listener({ type: 'POPUP_TRIGGER' }, {}, sendResponse);

      // Must return true to keep the channel open for async response
      expect(returnVal).toBe(true);

      await new Promise(resolve => setTimeout(resolve, 0));

      expect(chrome.tabs.query).toHaveBeenCalledWith(
        { active: true, currentWindow: true },
        expect.any(Function)
      );
    });

    it('responds with ok:false when no active tab is found', async () => {
      loadBackground();
      const listener = getMessageListener();

      chrome.tabs.query.mockImplementation((_q, cb) => cb([]));

      const sendResponse = jest.fn();
      listener({ type: 'POPUP_TRIGGER' }, {}, sendResponse);

      await new Promise(resolve => setTimeout(resolve, 0));

      expect(sendResponse).toHaveBeenCalledWith({ ok: false, error: 'No active tab' });
    });
  });

  describe('GET_JOB_STATUS', () => {
    it('resolves job state from storage and calls sendResponse', async () => {
      loadBackground();
      const listener = getMessageListener();

      // Pre-populate storage
      const stored = { status: 'processing', jobId: 'abc' };
      _storageMock.get.mockImplementation((key, cb) => cb({ [key]: stored }));

      const sendResponse = jest.fn();
      listener({ type: 'GET_JOB_STATUS', tabId: 7 }, {}, sendResponse);

      await new Promise(resolve => setTimeout(resolve, 0));

      expect(sendResponse).toHaveBeenCalledWith(stored);
    });

    it('returns null when no state is stored for the tab', async () => {
      loadBackground();
      const listener = getMessageListener();

      _storageMock.get.mockImplementation((_key, cb) => cb({}));

      const sendResponse = jest.fn();
      listener({ type: 'GET_JOB_STATUS', tabId: 99 }, {}, sendResponse);

      await new Promise(resolve => setTimeout(resolve, 0));

      expect(sendResponse).toHaveBeenCalledWith(null);
    });
  });

  describe('CLEAR_JOB', () => {
    it('removes the stored state and responds ok', async () => {
      loadBackground();
      const listener = getMessageListener();

      const sendResponse = jest.fn();
      listener({ type: 'CLEAR_JOB', tabId: 5 }, {}, sendResponse);

      await new Promise(resolve => setTimeout(resolve, 0));

      expect(chrome.storage.local.remove).toHaveBeenCalledWith(stateKey(5), expect.any(Function));
      expect(sendResponse).toHaveBeenCalledWith({ ok: true });
    });
  });

  describe('unknown message type', () => {
    it('does not throw and returns undefined', () => {
      loadBackground();
      const listener = getMessageListener();

      expect(() =>
        listener({ type: 'UNKNOWN_TYPE' }, {}, jest.fn())
      ).not.toThrow();
    });
  });
});

// ── tabs.onUpdated ─────────────────────────────────────────────────────────────

describe('tabs.onUpdated', () => {
  it('clears job state when a tab starts loading', () => {
    loadBackground();

    const calls = chrome.tabs.onUpdated.addListener.mock.calls;
    const updatedListener = calls[calls.length - 1][0];

    updatedListener(10, { status: 'loading' });
    expect(chrome.storage.local.remove).toHaveBeenCalledWith(stateKey(10), expect.any(Function));
  });

  it('does nothing for non-loading changeInfo', () => {
    loadBackground();

    const calls = chrome.tabs.onUpdated.addListener.mock.calls;
    const updatedListener = calls[calls.length - 1][0];

    updatedListener(10, { status: 'complete' });
    expect(chrome.storage.local.remove).not.toHaveBeenCalled();
  });
});

// ── contextMenu trigger ────────────────────────────────────────────────────────

describe('contextMenus.onClicked', () => {
  it('calls triggerExtraction for the oc-send-jd item', async () => {
    loadBackground();

    const handler = getContextMenuListener();
    const tab = { id: 1, title: 'A Job', url: 'https://example.com' };

    chrome.tabs.sendMessage.mockResolvedValue({ alive: true });
    chrome.tabs.sendMessage.mockResolvedValueOnce({ alive: true });
    chrome.tabs.sendMessage.mockResolvedValueOnce({
      success: false, error: 'Extraction failed',
    });

    await handler({ menuItemId: 'oc-send-jd' }, tab);

    // setJobState should have written EXTRACTING status
    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(1)]: expect.objectContaining({ status: 'extracting' }),
      }),
      expect.any(Function)
    );
  });

  it('ignores clicks for unrelated menu items', async () => {
    loadBackground();

    const handler = getContextMenuListener();
    await handler({ menuItemId: 'something-else' }, { id: 1 });

    expect(chrome.storage.local.set).not.toHaveBeenCalled();
  });
});

// ── triggerExtraction: content script injection ────────────────────────────────

describe('triggerExtraction — injection path', () => {
  it('injects scripts when PING fails', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 3, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    // PING throws (content script not present)
    chrome.tabs.sendMessage
      .mockRejectedValueOnce(new Error('Could not establish connection'))
      // EXTRACT_JD after injection fails with short body
      .mockResolvedValueOnce({ success: true, jd: { body: 'short', title: '', company: '', location: '', url: '', site: '' } });

    chrome.scripting.executeScript.mockResolvedValue([]);

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());

    await new Promise(resolve => setTimeout(resolve, 50));

    expect(chrome.scripting.executeScript).toHaveBeenCalledWith(
      expect.objectContaining({
        target: { tabId: 3 },
        files: expect.arrayContaining(['content_script.js']),
      })
    );
  });

  it('sets ERROR state when script injection itself fails', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 4, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    chrome.tabs.sendMessage.mockRejectedValueOnce(new Error('No content script'));
    chrome.scripting.executeScript.mockRejectedValueOnce(new Error('Cannot inject into chrome://'));

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());

    await new Promise(resolve => setTimeout(resolve, 50));

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(4)]: expect.objectContaining({ status: 'error' }),
      }),
      expect.any(Function)
    );
  });
});

// ── submitToBridge ─────────────────────────────────────────────────────────────

describe('triggerExtraction — bridge submission', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
  });

  it('sets ERROR state when JD body is shorter than MIN_JD_CHARS', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 5, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    chrome.tabs.sendMessage
      .mockResolvedValueOnce({ alive: true }) // PING
      .mockResolvedValueOnce({
        success: true,
        jd: { body: 'too short', title: 'T', company: 'C', location: 'L', url: 'U', site: 'S' },
      }); // EXTRACT_JD

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(5)]: expect.objectContaining({ status: 'error' }),
      }),
      expect.any(Function)
    );
  });

  it('sets ERROR state when bridge API is unreachable', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 6, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    const longBody = 'A'.repeat(300);
    chrome.tabs.sendMessage
      .mockResolvedValueOnce({ alive: true })
      .mockResolvedValueOnce({
        success: true,
        jd: { body: longBody, title: 'T', company: 'C', location: 'L', url: 'U', site: 'S' },
      });

    global.fetch.mockRejectedValueOnce(new Error('fetch failed'));

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(resolve => setTimeout(resolve, 50));

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(6)]: expect.objectContaining({
          status: 'error',
          error: expect.stringContaining('Bridge API unreachable'),
        }),
      }),
      expect.any(Function)
    );
  });

  it('sets PROCESSING state and starts polling after successful bridge submission', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 7, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    const longBody = 'A'.repeat(300);
    chrome.tabs.sendMessage
      .mockResolvedValueOnce({ alive: true })
      .mockResolvedValueOnce({
        success: true,
        jd: { body: longBody, title: 'MyRole', company: 'MyCo', location: 'NYC', url: 'U', site: 'S' },
      });

    global.fetch
      // POST /api/jobs
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ job_id: 'job-123' }),
      })
      // First poll — still processing
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ status: 'processing', progress_message: 'Working…' }),
      });

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(resolve => setTimeout(resolve, 50));

    const setCalls = chrome.storage.local.set.mock.calls.map(c => c[0]);
    const processingCall = setCalls.find(
      c => c[stateKey(7)]?.status === 'processing'
    );
    expect(processingCall).toBeDefined();
    expect(processingCall[stateKey(7)].jobId).toBe('job-123');
  });
});

// ── pollForCompletion ──────────────────────────────────────────────────────────

// Helper: flush all pending microtasks and advance fake timers past one poll tick.
// jest.advanceTimersByTimeAsync (Jest 29+) interleaves microtask flushing with
// timer advancement, so we don't need manual Promise.resolve() juggling.
async function driveOnePollTick() {
  // Flush the async await chain inside triggerExtraction
  for (let i = 0; i < 20; i++) await Promise.resolve();
  // Advance past POLL_INTERVAL_MS (5 000 ms) so the tick callback fires
  await jest.advanceTimersByTimeAsync(5100);
  // Flush fetch promise and storage callbacks
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

describe('pollForCompletion', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    global.fetch = jest.fn();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('sets COMPLETE state and fires notification when bridge returns complete', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 8, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    const longBody = 'A'.repeat(300);
    chrome.tabs.sendMessage
      .mockResolvedValueOnce({ alive: true })
      .mockResolvedValueOnce({
        success: true,
        jd: { body: longBody, title: 'MyRole', company: 'MyCo', location: 'NYC', url: 'U', site: 'S' },
      });

    global.fetch
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ job_id: 'job-456' }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          status: 'complete',
          title: 'MyRole',
          company: 'MyCo',
          artifacts: { resume_pdf: 'https://cdn/resume.pdf' },
        }),
      });

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await driveOnePollTick();

    const setCalls = chrome.storage.local.set.mock.calls.map(c => c[0]);
    const completedCall = setCalls.find(c => c[stateKey(8)]?.status === 'complete');
    expect(completedCall).toBeDefined();
    expect(completedCall[stateKey(8)].artifacts).toEqual({ resume_pdf: 'https://cdn/resume.pdf' });
    expect(chrome.notifications.create).toHaveBeenCalled();
  }, 15_000);

  it('sets ERROR state when bridge returns error status', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 9, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));

    const longBody = 'A'.repeat(300);
    chrome.tabs.sendMessage
      .mockResolvedValueOnce({ alive: true })
      .mockResolvedValueOnce({
        success: true,
        jd: { body: longBody, title: 'T', company: 'C', location: 'L', url: 'U', site: 'S' },
      });

    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ job_id: 'job-err' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'error', error: 'Pipeline failed' }) });

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await driveOnePollTick();

    const setCalls = chrome.storage.local.set.mock.calls.map(c => c[0]);
    const errorCall = setCalls.find(c => c[stateKey(9)]?.status === 'error');
    expect(errorCall).toBeDefined();
    expect(errorCall[stateKey(9)].error).toBe('Pipeline failed');
  }, 15_000);
});
