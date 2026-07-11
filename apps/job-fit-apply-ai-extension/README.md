# OpenClaw JD Capture — Chrome Extension

Extracts job descriptions from any job board and ships them to your OpenClaw pipeline for resume and cover letter generation.

## Features

- **Smart Extraction** — Automatically detects and extracts job descriptions from 13+ major ATS platforms
- **Multiple Input Methods** — Use the toolbar popup or right-click context menu
- **Real-time Progress** — Track the 4-stage pipeline: Extract → Submit → Generate → Done
- **Download Artifacts** — Get resume and cover letter PDFs directly from the extension
- **Polling Support** — Automatic status checking with configurable intervals

## Supported Job Boards

| Site | Extractor |
|------|-----------|
| greenhouse.io | greenhouse |
| lever.co | lever |
| workday.com / myworkdayjobs.com | workday |
| linkedin.com | linkedin |
| indeed.com | indeed |
| glassdoor.com | glassdoor |
| ashbyhq.com | ashby |
| workable.com | workable |
| smartrecruiters.com | smartrecruiters |
| icims.com | icims |
| bamboohr.com | bamboohr |
| jazz.co | jazzhr |
| *All other sites* | generic |

## Prerequisites

- Google Chrome browser (version 88 or higher for MV3 support)
- Node.js 18+ (for development and testing)
- Access to an OpenClaw Bridge API instance

## Installation

### Development (Unpacked)

1. Open `chrome://extensions`
2. Enable **Developer mode** (toggle in top right)
3. Click **Load unpacked** → select this project folder
4. Pin the extension to your toolbar for easy access

### Production Build

```bash
# Install dependencies
npm install

# Build for production
npm run build
```

The production build will be available in the `dist/` folder as a `.zip` file.

## Usage

### Method 1: Toolbar Popup

1. Navigate to any job listing page
2. Click the 🦞 extension icon in your toolbar
3. Review the extracted job description
4. Click **Generate Resume & Cover Letter**
5. Wait for processing to complete
6. Download your artifacts when ready

### Method 2: Context Menu

1. Navigate to any job listing page
2. Right-click anywhere on the page
3. Select **Send to OpenClaw → Generate Resume**

### Pipeline Stages

The extension displays real-time progress through these stages:

| Stage | Description |
|-------|-------------|
| Extract | Parsing job description from the page |
| Submit | Sending data to Bridge API |
| Generate | Server-side resume/cover letter generation |
| Done | Artifacts ready for download |

## Configuration

Edit `config.js` to customize behavior:

```javascript
// Bridge API endpoint
const BRIDGE_API_URL = 'http://your-api-endpoint:8765';

// Polling configuration (in milliseconds)
const POLL_INTERVAL_MS = 5000;  // How often to check status
const POLL_TIMEOUT_MS = 300000; // 5 minutes hard timeout

// Extraction settings
const MIN_JD_CHARS = 150;  // Minimum characters for valid JD
```

### Environment Variables

For sensitive configuration, create a `.env.local` file:

```bash
BRIDGE_API_URL=http://your-api-endpoint:8765
POLL_INTERVAL_MS=5000
POLL_TIMEOUT_MS=300000
```

## Project Structure

```
.
├── manifest.json          # Chrome extension manifest (MV3)
├── config.js              # Configuration and constants
├── background.js          # Service worker: menus, API calls, polling
├── content_script.js      # Page-injected listener for extraction
├── extractors/
│   └── jd_extractor.js    # Smart per-site JD extraction logic
├── popup/
│   ├── popup.html         # Extension popup UI
│   ├── popup.css          # Popup styling
│   └── popup.js           # Popup logic
├── icons/                 # Extension icons (16/32/48/128px)
├── tests/                 # Test suite
│   ├── setup.js           # Test configuration
│   ├── content_script.test.js
│   └── extractors/
│       └── jd_extractor.test.js
└── .github/
    └── workflows/         # CI/CD configuration
```

## Bridge API Contract

### POST /api/jobs

Submit a new job for processing.

**Request:**
```json
{
  "jd_text": "Job description text...",
  "title": "Software Engineer",
  "company": "Acme Corp",
  "location": "Seattle, WA",
  "url": "https://example.com/job/123",
  "site": "greenhouse.io"
}
```

**Response:**
```json
{
  "job_id": "abc123"
}
```

### GET /api/jobs/{job_id}

Check job status and retrieve results.

**In-Progress Response:**
```json
{
  "status": "processing",
  "progress_message": "Scoring JD..."
}
```

**Completed Response:**
```json
{
  "status": "complete",
  "title": "Software Engineer",
  "artifacts": {
    "resume_pdf": "http://api:8765/api/jobs/abc123/resume.pdf",
    "cover_letter_txt": "http://api:8765/api/jobs/abc123/cover_letter.txt"
  }
}
```

**Error Response:**
```json
{
  "status": "error",
  "error": "Failed to parse job description"
}
```

## Testing

```bash
# Install dependencies
npm install

# Run all tests
npm test

# Run tests with coverage report
npm run test:coverage

# Run tests in watch mode (development)
npm run test:watch
```

### Writing Tests

- Unit tests for extractors are in `tests/extractors/`
- Integration tests for content script messaging are in `tests/content_script.test.js`
- Test setup is configured in `tests/setup.js`

## Troubleshooting

### Extension not loading

1. Ensure you're in Chrome developer mode
2. Click "Reload" on the extension page after changes
3. Check the Extensions Service Worker console for errors

### Extraction failing

1. Verify the page has a job description visible
2. Check that `MIN_JD_CHARS` threshold is appropriate
3. Try using the generic extractor for unsupported sites
4. Review browser console for extraction errors

### API connection issues

1. Verify `BRIDGE_API_URL` in `config.js` is correct
2. Ensure the Bridge API server is running
3. Check CORS settings on the API server
4. Increase `POLL_TIMEOUT_MS` if network is slow

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License.

## Related

- [OpenClaw Bridge API](https://github.com/your-org/openclaw-bridge) — Backend service
- [OpenClaw Resume Generator](https://github.com/your-org/openclaw-resume-gen) — Resume generation engine
