# Tests

This directory contains unit and integration tests for the jd-extension-page-to-pipeline Chrome extension.

## Test Structure

```
tests/
├── setup.js                    # Jest setup with Chrome API mocks
├── content_script.test.js      # Integration tests for content_script.js
├── extractors/
│   └── jd_extractor.test.js   # Unit tests for JD extractors
└── README.md                   # This file
```

## Running Tests

```bash
# Install dependencies
npm install

# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage report
npm run test:coverage

# Run linting
npm run lint
```

## Test Coverage

Tests cover:

- **extractors/jd_extractor.js**: DOM helper functions, site extractor selection, and extraction logic for:
  - Greenhouse
  - Lever
  - Workday
  - LinkedIn
  - Indeed
  - Glassdoor
  - Ashby
  - Workable
  - SmartRecruiters
  - iCIMS
  - BambooHR
  - JazzHR
  - Generic fallback

- **content_script.js**: Message handling (PING, EXTRACT_JD), retry logic, and waitForContent function

## CI/CD

Tests run automatically on:
- Push to `main` or `master` branches
- Pull requests to `main` or `master` branches

GitHub Actions workflow: `.github/workflows/test.yml`

## Writing Tests

### Unit Tests (extractors)

```javascript
const { JSDOM } = require('jsdom');

function createDoc(html) {
  return new JSDOM(html).window.document;
}

describe('Extractor', () => {
  it('extracts fields correctly', () => {
    const doc = createDoc('<div>...</div>');
    const result = EXTRACTORS.greenhouse(doc);
    expect(result.title).toBe('Expected Title');
  });
});
```

### Integration Tests (content script)

```javascript
describe('Content Script', () => {
  it('handles PING messages', () => {
    // Set up mocks and load content script
    const listener = chrome.runtime.onMessage.addListener.mock.calls[0][0];
    listener({ type: 'PING' }, {}, sendResponse);
    expect(responseSent).toEqual({ alive: true });
  });
});
```
