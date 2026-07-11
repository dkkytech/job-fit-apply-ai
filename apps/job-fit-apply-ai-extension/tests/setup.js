/**
 * tests/setup.js – Jest setup for jsdom environment.
 * Mocks the Chrome extension API for both content_script and background tests.
 */

const { TextEncoder, TextDecoder } = require('util');
global.TextEncoder = TextEncoder;
global.TextDecoder = TextDecoder;

// ── storage.local ─────────────────────────────────────────────────────────────
const _store = {};

const storageMock = {
  get: jest.fn((key, cb) => {
    cb({ [key]: _store[key] });
  }),
  set: jest.fn((obj, cb) => {
    Object.assign(_store, obj);
    if (cb) cb();
  }),
  remove: jest.fn((key, cb) => {
    delete _store[key];
    if (cb) cb();
  }),
  _reset: () => Object.keys(_store).forEach(k => delete _store[k]),
};

// ── notifications ─────────────────────────────────────────────────────────────
const notificationsMock = {
  create: jest.fn(),
};

// ── contextMenus ──────────────────────────────────────────────────────────────
const contextMenusMock = {
  remove: jest.fn((_id, cb) => { if (cb) cb(); }),
  create: jest.fn(),
  onClicked: { addListener: jest.fn() },
};

// ── scripting ─────────────────────────────────────────────────────────────────
const scriptingMock = {
  executeScript: jest.fn().mockResolvedValue([]),
};

// ── tabs ──────────────────────────────────────────────────────────────────────
const tabsMock = {
  query: jest.fn(),
  sendMessage: jest.fn(),
  onUpdated: { addListener: jest.fn() },
};

// ── runtime ───────────────────────────────────────────────────────────────────
const runtimeMock = {
  onInstalled: { addListener: jest.fn() },
  onMessage: { addListener: jest.fn() },
  sendMessage: jest.fn(),
  lastError: null,
};

global.chrome = {
  runtime: runtimeMock,
  storage: { local: storageMock },
  notifications: notificationsMock,
  contextMenus: contextMenusMock,
  scripting: scriptingMock,
  tabs: tabsMock,
};

// Expose store reset helper so tests can call chrome.storage.local._reset()
global._storageMock = storageMock;

// ── window.location ───────────────────────────────────────────────────────────
delete global.location;
global.location = new URL('https://www.linkedin.com/jobs/view/123');

// ── minimal DOM ───────────────────────────────────────────────────────────────
const { JSDOM } = require('jsdom');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>');
global.document = dom.window.document;
global.window = dom.window;
global.Node = dom.window.Node;
