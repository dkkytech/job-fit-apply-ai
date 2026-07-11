/**
 * extractors/jd_extractor.js
 *
 * Loaded as a content script on every page. Exposes window.__ocExtractJD()
 * which returns a structured JD payload or throws if the page isn't a job listing.
 *
 * Each site extractor returns: { title, company, location, body }
 * The wrapper adds: { url, site, extractorUsed, extractedAt, wordCount }
 */

const EXTRACTORS = {

  // ─── Greenhouse ────────────────────────────────────────────────────────────
  greenhouse(doc) {
    const title   = t(doc, '#app_body h1, .app-title h1, [data-qa="job-title"]');
    const company = t(doc, '.company-name, [data-qa="company-name"], .app-title .company');
    const location= t(doc, '.location, [data-qa="location"]');
    const body    = inner(doc, '#content, .job-post, #app_body .section-wrapper, #app_body');
    return { title, company, location, body };
  },

  // ─── Lever ─────────────────────────────────────────────────────────────────
  lever(doc) {
    const title   = t(doc, '.posting-headline h2, h2[data-qa="posting-name"], .posting-name');
    const company = t(doc, '.main-header-text .posting-category, .posting-categories .location');
    const location= t(doc, '.posting-categories .location, .workplaceTypes');
    const body    = inner(doc, '.posting-page .section-wrapper, .posting-description, .content');
    return { title, company, location, body };
  },

  // ─── Workday / myworkdayjobs ───────────────────────────────────────────────
  workday(doc) {
    const title   = t(doc, '[data-automation-id="jobPostingHeader"], h2.css-129m7dg, h1[data-automation-id]');
    const company = t(doc, '[data-automation-id="company-name"], [data-automation-id="companyName"]');
    const location= t(doc, '[data-automation-id="locations"], [data-automation-id="location"]');
    const body    = inner(doc, '[data-automation-id="job-posting-details"], .css-cygeeu, [data-automation-id="jobPostingDescription"]');
    return { title, company, location, body };
  },

  // ─── LinkedIn ──────────────────────────────────────────────────────────────
  linkedin(doc) {
    const title   = t(doc, '.job-details-jobs-unified-top-card__job-title h1, h1.t-24, .jobs-unified-top-card__job-title');
    const company = t(doc, '.job-details-jobs-unified-top-card__company-name a, .jobs-unified-top-card__company-name');
    const location= t(doc, '.job-details-jobs-unified-top-card__bullet, .jobs-unified-top-card__bullet');
    const body    = inner(doc, '.jobs-description-content__text, .description__text, #job-details, .jobs-description__container');
    return { title, company, location, body };
  },

  // ─── Indeed ────────────────────────────────────────────────────────────────
  indeed(doc) {
    const title   = t(doc, '[data-testid="jobsearch-JobInfoHeader-title"], h1#jobsearch-JobInfoHeader-title, h1.jobsearch-JobInfoHeader-title');
    const company = t(doc, '[data-testid="inlineHeader-companyName"] a, [data-testid="inlineHeader-companyName"], .jobsearch-InlineCompanyRating-companyHeader');
    const location= t(doc, '[data-testid="job-location"], .jobsearch-JobInfoHeader-subtitle div:last-child');
    const body    = inner(doc, '#jobDescriptionText, .jobsearch-jobDescriptionText, [data-testid="job-description"]');
    return { title, company, location, body };
  },

  // ─── Glassdoor ─────────────────────────────────────────────────────────────
  glassdoor(doc) {
    const title   = t(doc, '[data-test="job-title"], h1[class*="JobTitle"]');
    const company = t(doc, '[data-test="employer-name"], [class*="EmployerName"]');
    const location= t(doc, '[data-test="location"], [class*="location"]');
    const body    = inner(doc, '.jobDescriptionContent, [data-test="description"], [class*="JobDescription"]');
    return { title, company, location, body };
  },

  // ─── Ashby ─────────────────────────────────────────────────────────────────
  ashby(doc) {
    const title   = t(doc, 'h1.ashby-job-posting-heading, h1[class*="PostingTitle"]');
    const company = t(doc, '.ashby-job-posting-company-name, [class*="CompanyName"]');
    const location= t(doc, '[class*="Location"], [class*="location"]');
    const body    = inner(doc, '.ashby-job-posting-description, [class*="JobPosting_description"], [class*="PostingDescription"]');
    return { title, company, location, body };
  },

  // ─── Workable ──────────────────────────────────────────────────────────────
  workable(doc) {
    const title   = t(doc, 'h1[data-ui="job-title"], h1.job-title');
    const company = t(doc, '[data-ui="company-name"], .company-name');
    const location= t(doc, '[data-ui="job-location"], .job-location');
    const body    = inner(doc, '[data-ui="job-description"], section.job-section');
    return { title, company, location, body };
  },

  // ─── SmartRecruiters ───────────────────────────────────────────────────────
  smartrecruiters(doc) {
    const title   = t(doc, 'h1[data-ui="job-title"], h1.job-title, .details-title h1');
    const company = t(doc, '[data-ui="company-name"], .company-name');
    const location= t(doc, '[data-ui="job-location"]');
    const body    = inner(doc, '[data-ui="job-description"], .jobad-details-content, .job-sections');
    return { title, company, location, body };
  },

  // ─── iCIMS ─────────────────────────────────────────────────────────────────
  icims(doc) {
    const title   = t(doc, '.iCIMS_InfoMsg_Job h1, .job-title h1, h1.iCIMS_Header');
    const company = null;
    const location= t(doc, '.iCIMS_InfoMsg_Job .field-location');
    const body    = inner(doc, '.iCIMS_JobContent, #iCIMS_Content, .iCIMS_Expandable_Container');
    return { title, company, location, body };
  },

  // ─── BambooHR ──────────────────────────────────────────────────────────────
  bamboohr(doc) {
    const title   = t(doc, 'h2.Section-title, h1.BambooHR-ATS-Title');
    const company = null;
    const location= t(doc, '.BambooHR-ATS-Location');
    const body    = inner(doc, '#BambooHR-ATS, .BambooHR-ATS-body');
    return { title, company, location, body };
  },

  // ─── JazzHR ────────────────────────────────────────────────────────────────
  jazzhr(doc) {
    const title   = t(doc, 'h1.position-title');
    const company = t(doc, '.company-name, h3.company');
    const location= t(doc, '.location');
    const body    = inner(doc, '.position-description, .apply-description');
    return { title, company, location, body };
  },

  // ─── Generic Fallback ──────────────────────────────────────────────────────
  generic(doc) {
    // Score candidate container elements by heuristics
    const candidates = [
      ...doc.querySelectorAll(
        'main, article, [role="main"], ' +
        '#job-description, .job-description, .job-details, ' +
        '#description, .description, .posting-content, ' +
        '[class*="job"], [id*="job"]'
      )
    ].filter(el => {
      const text = el.innerText || '';
      return text.length > 250; // must have substance
    });

    // Prefer the element with the richest text that isn't the whole body
    const best = candidates
      .filter(el => el !== doc.body)
      .sort((a, b) => (b.innerText || '').length - (a.innerText || '').length)[0];

    const title    = t(doc, 'h1') || t(doc, 'h2');
    const company  = t(doc, '[class*="company"], [id*="company"]');
    const location = t(doc, '[class*="location"], [id*="location"]');
    const body     = (best?.innerText || doc.body.innerText || '').trim();

    return { title, company, location, body };
  },
};

// ── Site → extractor routing ─────────────────────────────────────────────────

const SITE_MAP = [
  [/greenhouse\.io/,        'greenhouse'      ],
  [/lever\.co/,             'lever'           ],
  [/workday\.com/,          'workday'         ],
  [/myworkdayjobs\.com/,    'workday'         ],
  [/linkedin\.com/,         'linkedin'        ],
  [/indeed\.com/,           'indeed'          ],
  [/glassdoor\.com/,        'glassdoor'       ],
  [/ashbyhq\.com/,          'ashby'           ],
  [/workable\.com/,         'workable'        ],
  [/smartrecruiters\.com/,  'smartrecruiters' ],
  [/icims\.com/,            'icims'           ],
  [/bamboohr\.com/,         'bamboohr'        ],
  [/jazz\.co/,              'jazzhr'          ],
];

function pickExtractor(hostname) {
  for (const [pattern, name] of SITE_MAP) {
    if (pattern.test(hostname)) return name;
  }
  return 'generic';
}

// ── DOM helpers ───────────────────────────────────────────────────────────────

function t(doc, selector) {
  return doc.querySelector(selector)?.textContent?.trim() || null;
}

function inner(doc, selector) {
  // Try each comma-separated selector, return the first hit with real content
  for (const sel of selector.split(',').map(s => s.trim())) {
    const el = doc.querySelector(sel);
    if (el?.innerText?.trim().length > 50) return el.innerText.trim();
  }
  return null;
}

// ── Public API ────────────────────────────────────────────────────────────────

window.__ocExtractJD = function extractJD() {
  const hostname     = window.location.hostname;
  const extractorKey = pickExtractor(hostname);
  const extractor    = EXTRACTORS[extractorKey];

  const { title, company, location, body } = extractor(document);

  if (!body || body.length < 150) {
    throw new Error(
      `Extraction yielded too little text (${body?.length ?? 0} chars). ` +
      `This page may not be a job listing.`
    );
  }

  return {
    title:        title   || document.title || null,
    company:      company || null,
    location:     location|| null,
    body,
    wordCount:    body.split(/\s+/).length,
    url:          window.location.href,
    site:         hostname,
    extractorUsed: extractorKey,
    extractedAt:  new Date().toISOString(),
  };
};

module.exports = {
  EXTRACTORS,
  pickExtractor,
  t,
  inner,
};
