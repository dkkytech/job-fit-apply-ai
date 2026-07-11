// config.js — shared constants for the extension
//
// BRIDGE_API_URL: Uses MagicDNS for direct WireGuard/tailnet access.
// The hostname 'richards-macbook-m1-max.tail02d0e.ts.net' resolves to the
// machine's Tailscale IP (100.111.66.8) via the tailnet DNS, ensuring traffic
// stays within the private tailnet without going through Tailscale Funnel.

export const BRIDGE_API_URL = 'http://richards-macbook-m1-max.tail02d0e.ts.net:8765';

export const POLL_INTERVAL_MS  = 5_000;   // how often to check job status
export const POLL_TIMEOUT_MS   = 300_000; // 5-minute hard timeout
export const MIN_JD_CHARS      = 150;     // reject extractions shorter than this

export const STATUS = {
  IDLE:        'idle',
  EXTRACTING:  'extracting',
  SUBMITTING:  'submitting',
  PROCESSING:  'processing',
  COMPLETE:    'complete',
  ERROR:       'error',
};
