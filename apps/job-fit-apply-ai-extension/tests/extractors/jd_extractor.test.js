/**
 * tests/extractors/jd_extractor.test.js
 *
 * Unit tests for extractors/jd_extractor.js
 */

const { JSDOM } = require('jsdom');

// Patch Element.prototype.innerText for JSDOM (it doesn't implement innerText properly)
// This ensures inner() helper tests work correctly
const originalGetter = Object.getOwnPropertyDescriptor(Element.prototype, 'innerText');
Object.defineProperty(Element.prototype, 'innerText', {
  get() {
    return this.textContent;
  },
  configurable: true,
});

afterAll(() => {
  // Restore original innerText if it existed
  if (originalGetter) {
    Object.defineProperty(Element.prototype, 'innerText', originalGetter);
  }
});

// Helper to create a minimal DOM from HTML string
function createDoc(html) {
  const dom = new JSDOM(html);
  const { window } = dom;
  
  // Patch innerText on JSDOM's Element prototype (JSDOM doesn't implement innerText)
  // This must be done on the JSDOM window's prototype chain, not the global one
  const originalDescriptor = Object.getOwnPropertyDescriptor(window.Element.prototype, 'innerText');
  Object.defineProperty(window.Element.prototype, 'innerText', {
    get() {
      return this.textContent;
    },
    configurable: true,
  });
  
  return window.document;
}

// Load the extractor module
const extractorPath = require.resolve('../../extractors/jd_extractor.js');
const { EXTRACTORS, pickExtractor, t, inner } = require(extractorPath);

describe('JD Extractor', () => {
  describe('t() helper', () => {
    it('returns trimmed text content of first matching selector', () => {
      const doc = createDoc('<div><span id="title">  Senior SDET  </span></div>');
      expect(t(doc, '#title')).toBe('Senior SDET');
    });

    it('returns null when no selector matches', () => {
      const doc = createDoc('<div>Hello</div>');
      expect(t(doc, '#nonexistent')).toBeNull();
    });

    it('returns null for empty elements', () => {
      const doc = createDoc('<div id="empty"></div>');
      expect(t(doc, '#empty')).toBeNull();
    });
  });

  describe('inner() helper', () => {
    it('returns innerText of first selector with substantial content', () => {
      const doc = createDoc(`
        <article>
          <p class="desc">This is a job description with lots of content about the role.</p>
        </article>
      `);
      expect(inner(doc, '.desc')).toBe('This is a job description with lots of content about the role.');
    });

    it('returns null when no selector has > 50 chars', () => {
      const doc = createDoc('<p class="short">Short</p>');
      expect(inner(doc, '.short')).toBeNull();
    });

    it('tries multiple selectors and returns first hit', () => {
      const doc = createDoc('<div class="second">Good content here that is more than fifty characters for the test</div>');
      expect(inner(doc, '.first, .second')).toBeTruthy();
    });
  });

  describe('pickExtractor()', () => {
    it('returns greenhouse for greenhouse.io', () => {
      expect(pickExtractor('jobs.greenhouse.io')).toBe('greenhouse');
      expect(pickExtractor('apply.greenhouse.io')).toBe('greenhouse');
    });

    it('returns lever for lever.co', () => {
      expect(pickExtractor('jobs.lever.co')).toBe('lever');
      expect(pickExtractor('apply.lever.co')).toBe('lever');
    });

    it('returns workday for workday.com', () => {
      expect(pickExtractor('wd3.myworkday.com')).toBe('workday');
      expect(pickExtractor('workday.com')).toBe('workday');
    });

    it('returns linkedin for linkedin.com', () => {
      expect(pickExtractor('www.linkedin.com')).toBe('linkedin');
      expect(pickExtractor('linkedin.com/jobs')).toBe('linkedin');
    });

    it('returns indeed for indeed.com', () => {
      expect(pickExtractor('www.indeed.com')).toBe('indeed');
    });

    it('returns glassdoor for glassdoor.com', () => {
      expect(pickExtractor('www.glassdoor.com')).toBe('glassdoor');
    });

    it('returns ashby for ashbyhq.com', () => {
      expect(pickExtractor('jobs.ashbyhq.com')).toBe('ashby');
    });

    it('returns workable for workable.com', () => {
      expect(pickExtractor('apply.workable.com')).toBe('workable');
    });

    it('returns smartrecruiters for smartrecruiters.com', () => {
      expect(pickExtractor('jobs.smartrecruiters.com')).toBe('smartrecruiters');
    });

    it('returns icims for icims.com', () => {
      expect(pickExtractor('jobs.icims.com')).toBe('icims');
    });

    it('returns bamboohr for bamboohr.com', () => {
      expect(pickExtractor('*.bamboohr.com')).toBe('bamboohr');
    });

    it('returns jazzhr for jazz.co', () => {
      expect(pickExtractor('apply.jazz.co')).toBe('jazzhr');
    });

    it('returns generic for unknown sites', () => {
      expect(pickExtractor('example.com')).toBe('generic');
      expect(pickExtractor('randomjobsite.com')).toBe('generic');
    });
  });

  describe('Greenhouse extractor', () => {
    const extractor = EXTRACTORS.greenhouse;

    it('extracts title, company, location, and body', () => {
      const doc = createDoc(`
        <div id="app_body">
          <h1 class="app-title h1">Senior SDET Engineer</h1>
          <span class="company-name">Acme Corp</span>
          <span class="location">Seattle, WA</span>
          <div id="content">
            We are looking for a talented Senior SDET Engineer to join our team.
            You will be responsible for building and maintaining test automation frameworks.
            The ideal candidate has 5+ years of experience in test automation.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Senior SDET Engineer');
      expect(result.company).toBe('Acme Corp');
      expect(result.location).toBe('Seattle, WA');
      expect(result.body).toContain('Senior SDET Engineer');
    });
  });

  describe('Lever extractor', () => {
    const extractor = EXTRACTORS.lever;

    it('extracts fields from Lever job pages', () => {
      const doc = createDoc(`
        <div class="posting-headline">
          <h2>Staff QA Engineer</h2>
        </div>
        <div class="main-header-text">
          <span class="posting-category">Acme Labs</span>
        </div>
        <div class="posting-categories">
          <span class="location">Remote</span>
        </div>
        <div class="posting-page">
          <div class="section-wrapper">
            <div class="posting-description">
              We need a Staff QA Engineer to lead our quality initiatives.
            </div>
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Staff QA Engineer');
      expect(result.body).toContain('Staff QA Engineer');
    });
  });

  describe('LinkedIn extractor', () => {
    const extractor = EXTRACTORS.linkedin;

    it('extracts fields from LinkedIn job pages', () => {
      const doc = createDoc(`
        <div class="jobs-unified-top-card">
          <h1 class="jobs-unified-top-card__job-title">Principal SDET</h1>
          <span class="jobs-unified-top-card__company-name">
            <a href="/company/acme">Acme Inc</a>
          </span>
          <span class="jobs-unified-top-card__bullet">San Francisco, CA</span>
        </div>
        <div class="jobs-description-content__text">
          <p>Job description content here with lots of details about the role...</p>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Principal SDET');
      expect(result.company).toContain('Acme Inc');
      expect(result.body).toContain('Job description');
    });
  });

  describe('Indeed extractor', () => {
    const extractor = EXTRACTORS.indeed;

    it('extracts fields from Indeed job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 id="jobsearch-JobInfoHeader-title" data-testid="jobsearch-JobInfoHeader-title">
            Test Automation Engineer
          </h1>
          <div data-testid="inlineHeader-companyName">
            <a href="/company/indeed">Indeed</a>
          </div>
          <div data-testid="job-location">Austin, TX</div>
          <div id="jobDescriptionText">
            This is a great opportunity for a test automation engineer.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Test Automation Engineer');
      expect(result.body).toContain('test automation engineer');
    });
  });

  describe('Generic extractor', () => {
    const extractor = EXTRACTORS.generic;

    it('falls back to generic extraction with heuristics', () => {
      const doc = createDoc(`
        <main>
          <h1>QA Lead Position</h1>
          <div id="company-name">TechCorp</div>
          <div id="job-description">
            <p>We are hiring a QA Lead to manage our test team.</p>
            <p>The ideal candidate has experience with Selenium, Cypress, and test strategy.</p>
          </div>
        </main>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('QA Lead Position');
      expect(result.body.length).toBeGreaterThan(100);
    });

    it('filters out elements with less than 300 characters', () => {
      const doc = createDoc(`
        <div>
          <p class="sidebar">Short text</p>
          <article id="job-description">
            This is a much longer job description that contains detailed information about the role,
            responsibilities, requirements, and qualifications for the position. It should be
            more than three hundred characters long to pass the filter test and ensure the content
            extraction works properly with sufficient text content in the job description section.
          </article>
        </div>
      `);
      const result = extractor(doc);
      expect(result.body.length).toBeGreaterThanOrEqual(300);
    });
  });

  // ── Additional site extractors ──────────────────────────────────────────────

  describe('Workday extractor', () => {
    const extractor = EXTRACTORS.workday;

    it('extracts title, company, location and body via automation-id selectors', () => {
      const doc = createDoc(`
        <div>
          <h2 data-automation-id="jobPostingHeader">Senior QA Engineer</h2>
          <span data-automation-id="company-name">WorkdayCo</span>
          <span data-automation-id="locations">Remote</span>
          <div data-automation-id="job-posting-details">
            We are looking for a Senior QA Engineer to join our growing team and help
            us build world-class automated testing frameworks. You will design test
            strategies and mentor junior engineers across the organisation.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Senior QA Engineer');
      expect(result.company).toBe('WorkdayCo');
      expect(result.location).toBe('Remote');
      expect(result.body).toContain('QA Engineer');
    });

    it('returns nulls when selectors find nothing', () => {
      const doc = createDoc('<div></div>');
      const result = extractor(doc);
      expect(result.title).toBeNull();
      expect(result.body).toBeNull();
    });
  });

  describe('Glassdoor extractor', () => {
    const extractor = EXTRACTORS.glassdoor;

    it('extracts fields via data-test attributes', () => {
      const doc = createDoc(`
        <div>
          <h1 data-test="job-title">Principal Engineer</h1>
          <span data-test="employer-name">GlassCo</span>
          <span data-test="location">Austin, TX</span>
          <div data-test="description">
            We are hiring a Principal Engineer who will drive architectural decisions
            and lead teams in building scalable, reliable backend systems. You will
            collaborate with product and design to ship high-quality features.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Principal Engineer');
      expect(result.company).toBe('GlassCo');
      expect(result.location).toBe('Austin, TX');
      expect(result.body).toContain('Principal Engineer');
    });
  });

  describe('Ashby extractor', () => {
    const extractor = EXTRACTORS.ashby;

    it('extracts fields from Ashby job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 class="ashby-job-posting-heading">Staff Engineer</h1>
          <span class="ashby-job-posting-company-name">Ashby Inc</span>
          <div class="ashby-job-posting-description">
            Join our engineering team as a Staff Engineer and take ownership of
            critical infrastructure components. You will mentor senior engineers
            and drive cross-team technical initiatives that impact the entire platform.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Staff Engineer');
      expect(result.company).toBe('Ashby Inc');
      expect(result.body).toContain('Staff Engineer');
    });
  });

  describe('Workable extractor', () => {
    const extractor = EXTRACTORS.workable;

    it('extracts fields from Workable job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 data-ui="job-title">QA Automation Lead</h1>
          <span data-ui="company-name">WorkableCo</span>
          <span data-ui="job-location">New York, NY</span>
          <div data-ui="job-description">
            We are looking for an experienced QA Automation Lead to guide our testing
            strategy. You will build frameworks, mentor engineers, and drive quality
            across multiple product lines in an agile environment.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('QA Automation Lead');
      expect(result.company).toBe('WorkableCo');
      expect(result.location).toBe('New York, NY');
      expect(result.body).toContain('QA Automation Lead');
    });
  });

  describe('SmartRecruiters extractor', () => {
    const extractor = EXTRACTORS.smartrecruiters;

    it('extracts fields from SmartRecruiters job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 data-ui="job-title">Test Architect</h1>
          <span data-ui="company-name">SmartCo</span>
          <span data-ui="job-location">Chicago, IL</span>
          <div data-ui="job-description">
            As a Test Architect you will define the testing vision for our platform,
            design automation frameworks, and ensure test quality standards are met
            across all engineering teams in our distributed organisation.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Test Architect');
      expect(result.company).toBe('SmartCo');
      expect(result.body).toContain('Test Architect');
    });
  });

  describe('iCIMS extractor', () => {
    const extractor = EXTRACTORS.icims;

    it('extracts fields from iCIMS job pages (company is always null)', () => {
      const doc = createDoc(`
        <div class="iCIMS_InfoMsg_Job">
          <h1 class="iCIMS_Header">SDET II</h1>
          <span class="field-location">Denver, CO</span>
        </div>
        <div class="iCIMS_JobContent">
          This position is for an SDET II who will design and implement automated
          test suites for our mobile and web applications, collaborate closely with
          developers, and champion quality practices throughout the engineering org.
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('SDET II');
      expect(result.company).toBeNull();
      expect(result.location).toBe('Denver, CO');
      expect(result.body).toContain('SDET II');
    });
  });

  describe('BambooHR extractor', () => {
    const extractor = EXTRACTORS.bamboohr;

    it('extracts fields from BambooHR job pages (company is always null)', () => {
      const doc = createDoc(`
        <div>
          <h1 class="BambooHR-ATS-Title">Mobile QA Engineer</h1>
          <span class="BambooHR-ATS-Location">Portland, OR</span>
          <div id="BambooHR-ATS">
            We are hiring a Mobile QA Engineer to join our growing team. You will
            test iOS and Android applications, build automation frameworks using
            Appium, and collaborate with product teams to deliver high-quality apps.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('Mobile QA Engineer');
      expect(result.company).toBeNull();
      expect(result.location).toBe('Portland, OR');
      expect(result.body).toContain('Mobile QA Engineer');
    });
  });

  describe('JazzHR extractor', () => {
    const extractor = EXTRACTORS.jazzhr;

    it('extracts fields from JazzHR job pages', () => {
      const doc = createDoc(`
        <div>
          <h1 class="position-title">QA Engineer II</h1>
          <span class="company-name">JazzCo</span>
          <span class="location">Remote</span>
          <div class="position-description">
            We are looking for a QA Engineer II to join our product team. You will
            own end-to-end testing for multiple services, write automation tests,
            and participate in code reviews to ensure maintainable quality code.
          </div>
        </div>
      `);
      const result = extractor(doc);
      expect(result.title).toBe('QA Engineer II');
      expect(result.company).toBe('JazzCo');
      expect(result.location).toBe('Remote');
      expect(result.body).toContain('QA Engineer II');
    });
  });

  describe('Generic extractor — candidate sorting', () => {
    it('picks the longest candidate element when multiple qualify', () => {
      const shortText = 'A'.repeat(260); // > 250 chars, but shorter
      const longText  = 'B'.repeat(500); // longest
      const doc = createDoc(`
        <main>
          <article id="short">${shortText}</article>
          <article id="long">${longText}</article>
        </main>
      `);
      const result = EXTRACTORS.generic(doc);
      // body should come from the longest qualifying element
      expect(result.body).toContain('B');
      expect(result.body.length).toBeGreaterThanOrEqual(500);
    });
  });

  // ── window.__ocExtractJD ──────────────────────────────────────────────────────
  // These tests mutate document.body.innerHTML (same jsdom instance as the test
  // environment) so that innerText is patched via the global Element.prototype
  // at the top of this file, keeping DOM queries working correctly.

  describe('__ocExtractJD integration', () => {
    let savedBodyHTML;
    let savedTitle;

    beforeEach(() => {
      require(extractorPath); // ensure function is registered
      savedBodyHTML = document.body.innerHTML;
      savedTitle = document.title;
    });

    afterEach(() => {
      document.body.innerHTML = savedBodyHTML;
      document.title = savedTitle;
    });

    it('exposes __ocExtractJD as a global function', () => {
      expect(typeof window.__ocExtractJD).toBe('function');
    });

    it('returns structured JD payload for a recognised site (greenhouse)', () => {
      // location is already https://www.linkedin.com/... from setup.js;
      // Override to greenhouse so pickExtractor selects the right extractor.
      delete global.location;
      global.location = new URL('https://boards.greenhouse.io/acme/jobs/123');

      document.body.innerHTML = `
        <div id="app_body">
          <h1 class="app-title h1">Senior Test Engineer</h1>
          <span class="company-name">GreenhouseCo</span>
          <span class="location">Seattle, WA</span>
          <div id="content">
            We are hiring a Senior Test Engineer who will lead quality efforts across
            three product teams. You will design test strategies, build automation
            frameworks using Playwright and Cypress, mentor junior engineers, and
            champion a culture of quality throughout the engineering organisation.
          </div>
        </div>
      `;

      const result = window.__ocExtractJD();

      expect(result.extractorUsed).toBe('greenhouse');
      expect(result.title).toBe('Senior Test Engineer');
      expect(result.company).toBe('GreenhouseCo');
      expect(result.location).toBe('Seattle, WA');
      expect(result.body).toContain('Senior Test Engineer');
      expect(typeof result.wordCount).toBe('number');
      expect(result.wordCount).toBeGreaterThan(0);
      expect(result.url).toContain('greenhouse.io');
      expect(result.site).toBe('boards.greenhouse.io');
      expect(result.extractedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);

      // Restore location
      delete global.location;
      global.location = new URL('https://www.linkedin.com/jobs/view/123');
    });

    it('falls back to document.title when extractor returns no title', () => {
      delete global.location;
      global.location = new URL('https://example.com/job/42');
      document.title = 'Fallback Title From Document';

      document.body.innerHTML = `
        <main>
          ${'This is a generic job description with enough text to pass minimum. '.repeat(6)}
        </main>
      `;

      const result = window.__ocExtractJD();
      expect(result.title).toBe('Fallback Title From Document');

      delete global.location;
      global.location = new URL('https://www.linkedin.com/jobs/view/123');
    });

    it('throws when extracted body is shorter than 150 chars', () => {
      delete global.location;
      global.location = new URL('https://boards.greenhouse.io/acme/jobs/99');

      document.body.innerHTML = `
        <div id="app_body">
          <h1>Short Role</h1>
          <div id="content">Too short</div>
        </div>
      `;

      expect(() => window.__ocExtractJD()).toThrow(/too little text/i);

      delete global.location;
      global.location = new URL('https://www.linkedin.com/jobs/view/123');
    });
  });
});
