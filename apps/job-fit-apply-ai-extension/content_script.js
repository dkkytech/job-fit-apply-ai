/**
 * content_script.js
 *
 * Injected into every page alongside jd_extractor.js.
 * Listens for messages from the background service worker.
 */

global.chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {

  // ── PING — check if content script is alive ─────────────────────────────
  if (message.type === 'PING') {
    sendResponse({ alive: true });
    return true;
  }

  // ── EXTRACT_JD — run the smart extractor and return the payload ──────────
  // Uses a retry loop so JS-rendered SPAs have time to hydrate before we
  // give up. Retries every 500 ms for up to 8 seconds.
  if (message.type === 'EXTRACT_JD') {
    if (typeof global.window.__ocExtractJD !== 'function') {
      sendResponse({ success: false, error: 'Extractor not loaded. Try reloading the page.' });
      return true;
    }

    waitForContent()
      .then(jd  => sendResponse({ success: true, jd }))
      .catch(err => sendResponse({ success: false, error: err.message }));

    return true; // keep channel open for async sendResponse
  }
});

/**
 * Polls __ocExtractJD() every `intervalMs` until it returns a result with
 * enough content, or until `maxWaitMs` has elapsed.
 *
 * Because __ocExtractJD throws when body.length < MIN_JD_CHARS, the retry
 * loop naturally waits for SPA frameworks (React, Angular, Next.js, Workday)
 * to finish hydrating the DOM before declaring failure.
 */
function waitForContent(maxWaitMs = 8000, intervalMs = 500) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + maxWaitMs;

    const attempt = () => {
      try {
        const jd = global.window.__ocExtractJD();
        resolve(jd);
      } catch (err) {
        if (Date.now() >= deadline) {
          reject(new Error(
            `Page content didn't stabilize after ${maxWaitMs / 1000}s. ` +
            `Last error: ${err.message}`
          ));
        } else {
          setTimeout(attempt, intervalMs);
        }
      }
    };

    attempt();
  });
}

module.exports = { waitForContent };
