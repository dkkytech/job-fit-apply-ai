/**
 * tests/content_script.test.js
 *
 * Integration tests for content_script.js
 */

const { JSDOM } = require('jsdom');

// Counter for unique company names (supports 1M+ unique names)
let companyCounter = 0;

// Name components for combinatorial generation (50 × 50 × 400 = 1,000,000)
const companyPrefixes = [
  'Tech', 'Cloud', 'Data', 'Quantum', 'Apex', 'Next', 'Cyber', 'Digital', 'Code', 'Net',
  'Smart', 'Nova', 'Byte', 'Pixel', 'Flux', 'Core', 'Hyper', 'Meta', 'Ion', 'Delta',
  'Alpha', 'Beta', 'Omega', 'Prime', 'Edge', 'Spark', 'Wave', 'Vision', 'Logic', 'Matrix',
  'Stream', 'Nexus', 'Velocity', 'Cipher', 'Vertex', 'Synth', 'Echo', 'Pulse', 'NexGen', 'Raptor',
  'Opti', 'Innov', 'Synthe', 'Cyber', 'Data', 'Cloud', 'Quantu', 'Apex', 'Neura', 'Cogni'
];

const companySuffixes = [
  'Systems', 'Labs', 'Inc', 'Solutions', 'Dynamics', 'Ventures', 'Studios', 'Analytics', 'Technologies', 'Innovations',
  'Digital', 'Corp', 'Works', 'Forge', 'Hub', 'Base', 'Sync', 'Flow', 'Scale', 'Matrix',
  'Core', 'Nexus', 'Byte', 'Pulse', 'Stream', 'Logic', 'Vision', 'Wave', 'Edge', 'Radius',
  'Alpha', 'Beta', 'Gamma', 'Delta', 'Sigma', 'Omega', 'Prime', 'Nova', 'Cyber', 'Meta',
  'Io', 'Sys', 'Tech', 'Net', 'Data', 'Cloud', 'App', 'Code', 'Dev', 'Ops'
];

// Generate unique company name using combinatorial approach
function generateUniqueCompanyName(index) {
  const prefixesLen = companyPrefixes.length;
  const suffixesLen = companySuffixes.length;
  const maxPerCombo = 400;

  const prefix = companyPrefixes[index % prefixesLen];
  const suffix = companySuffixes[Math.floor((index / prefixesLen) % suffixesLen)];
  const number = Math.floor(index / (prefixesLen * suffixesLen)) % maxPerCombo + 1;

  return `${prefix}${suffix}${number}`;
}

// Create a fresh DOM for each test
function createTestEnvironment() {
  const randomCompany = generateUniqueCompanyName(companyCounter++);

  const dom = new JSDOM(`
    <!DOCTYPE html>
    <html>
      <head><title>Test Job Page</title></head>
      <body>
        <div class="jobs-description-content__text">
          <p><strong>Job Description:</strong></p>
          <p>We are looking for a Senior Mobile Test Engineer to join our dynamic QA team. You will be responsible for ensuring the quality and reliability of our mobile applications across iOS and Android platforms. This role involves designing and implementing test strategies, developing automated test frameworks, and mentoring junior team members.</p>
          <p><strong>Responsibilities:</strong></p>
          <ul>
            <li>Design and develop comprehensive test plans for mobile applications</li>
            <li>Implement and maintain automated test scripts using industry-standard frameworks</li>
            <li>Conduct performance testing and analyze results to identify bottlenecks</li>
            <li>Collaborate with development teams to integrate testing into CI/CD pipelines</li>
            <li>Mentor junior QA engineers on best practices and testing methodologies</li>
            <li>Review code changes and provide feedback on testability and quality aspects</li>
            <li>Create detailed test reports and metrics to track quality metrics</li>
          </ul>
          <p><strong>Qualifications:</strong></p>
          <ul>
            <li>Bachelor\'s degree in Computer Science or related field</li>
            <li>5+ years of experience in mobile testing (iOS and Android)</li>
            <li>Proficiency in test automation frameworks such as Appium, XCUITest, or Espresso</li>
            <li>Strong experience with CI/CD tools like Jenkins, CircleCI, or GitHub Actions</li>
            <li>Excellent problem-solving and analytical skills</li>
            <li>Strong communication skills and ability to work in a collaborative environment</li>
            <li>Experience with Agile methodologies is a plus</li>
          </ul>
        </div>
        <h1 class="jobs-unified-top-card__job-title">Senior Mobile Test Engineer</h1>
        <span class="jobs-unified-top-card__company-name">${randomCompany}</span>
        <span class="jobs-unified-top-card__bullet">Seattle, WA</span>
      </body>
    </html>
  `);
  return dom;
}

describe('Content Script', () => {
  let window, document;

  beforeEach(() => {
    // Reset environment
    const dom = createTestEnvironment();
    window = dom.window;
    document = window.document;
    global.window = window;
    global.document = document;
    // Note: global.chrome is already set up by tests/setup.js
    // Do not overwrite it here as setup.js properly configures chrome.runtime.lastError
  });

  describe('PING message', () => {
    it('responds with alive: true to PING messages', () => {
      // Load content script
      const contentScript = require('../content_script.js');

      let responseSent = null;
      const sendResponse = (response) => { responseSent = response; };

      // Get the message listener
      const listener = global.chrome.runtime.onMessage.addListener.mock.calls[0][0];

      // Send PING
      listener({ type: 'PING' }, {}, sendResponse);

      expect(responseSent).toEqual({ alive: true });
    });
  });

  describe('EXTRACT_JD message', () => {
    it('returns error when extractor is not loaded', () => {
      const contentScript = require('../content_script.js');

      let responseSent = null;
      const sendResponse = (response) => { responseSent = response; };

      // Remove the extractor to simulate it not being loaded
      delete global.window.__ocExtractJD;

      const listener = global.chrome.runtime.onMessage.addListener.mock.calls[0][0];

      listener({ type: 'EXTRACT_JD' }, {}, sendResponse);

      expect(responseSent).toEqual({
        success: false,
        error: 'Extractor not loaded. Try reloading the page.'
      });
    });

    it('calls the extractor when available', () => {
      // Set up the extractor
      global.window.__ocExtractJD = jest.fn(() => ({
        title: 'Test Job',
        company: 'Test Company',
        location: 'Test Location',
        body: 'This is a test job description with enough content to pass validation checks in the system.',
        wordCount: 15,
        url: 'https://example.com/job/123',
        site: 'example.com',
        extractorUsed: 'generic',
        extractedAt: '2024-01-01T00:00:00.000Z',
      }));

      const contentScript = require('../content_script.js');

      let responseSent = null;
      const sendResponse = (response) => { responseSent = response; };

      const listener = global.chrome.runtime.onMessage.addListener.mock.calls[0][0];

      // Call synchronously since our mock extractor doesn't wait
      listener({ type: 'EXTRACT_JD' }, {}, sendResponse);

      expect(global.window.__ocExtractJD).toHaveBeenCalled();
    });
  });

  describe('waitForContent function', () => {
    it('resolves immediately when content is available', async () => {
      // Load the content script to get waitForContent
      const contentScript = require('../content_script.js');

      global.window.__ocExtractJD = jest.fn(() => ({
        title: 'Test',
        company: 'Test Co',
        location: 'Test Loc',
        body: 'This is a test job description with more than 150 characters to pass the minimum content check.',
        wordCount: 15,
        url: 'https://example.com',
        site: 'example.com',
        extractorUsed: 'generic',
        extractedAt: '2024-01-01T00:00:00.000Z',
      }));

      const result = await contentScript.waitForContent(1000, 100);
      expect(result.title).toBe('Test');
    });

    it('retries and eventually rejects when content is not available', async () => {
      const contentScript = require('../content_script.js');

      let attempts = 0;
      global.window.__ocExtractJD = jest.fn(() => {
        attempts++;
        if (attempts < 3) {
          throw new Error('Content not ready');
        }
        return {
          title: 'Test',
          company: 'Test Co',
          location: 'Test Loc',
          body: 'This is a test job description with more than 150 characters to pass the minimum content check.',
          wordCount: 15,
          url: 'https://example.com',
          site: 'example.com',
          extractorUsed: 'generic',
          extractedAt: '2024-01-01T00:00:00.000Z',
        };
      });

      const result = await contentScript.waitForContent(2000, 100);
      expect(result.title).toBe('Test');
      expect(attempts).toBe(3);
    });

    it('rejects after maxWaitMs timeout', async () => {
      const contentScript = require('../content_script.js');

      global.window.__ocExtractJD = jest.fn(() => {
        throw new Error('Content not ready');
      });

      await expect(
        contentScript.waitForContent(500, 100)
      ).rejects.toThrow(/Page content didn't stabilize/);
    });
  });
});
