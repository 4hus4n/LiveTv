'use strict';

const { app, BrowserWindow, ipcMain, session, shell, net } = require('electron');
const path = require('path');
const fs = require('fs');
const { parseM3U } = require('./m3u');

const DEFAULT_UA = 'VLC/3.0.20 LibVLC/3.0.20';

let win = null;
// Headers for the channel that is currently playing (User-Agent / Referer from the playlist)
let streamCfg = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1400,
    height: 860,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#08090C',
    title: 'Live TV',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // IPTV servers rarely send CORS headers; this is a local, single-purpose app.
      webSecurity: false,
    },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
  win.on('enter-full-screen', () => win.webContents.send('fullscreen', true));
  win.on('leave-full-screen', () => win.webContents.send('fullscreen', false));
}

function setupNetwork() {
  const ses = session.defaultSession;
  const STREAM_TYPES = new Set(['xhr', 'media', 'other']);

  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    const h = details.requestHeaders;
    if (/^https?:/i.test(details.url) && STREAM_TYPES.has(details.resourceType)) {
      h['User-Agent'] = (streamCfg && streamCfg.ua) || DEFAULT_UA;
      delete h['Origin'];
      if (streamCfg && streamCfg.referrer) {
        h['Referer'] = streamCfg.referrer;
        try { h['Origin'] = new URL(streamCfg.referrer).origin; } catch (_) {}
      }
      if (streamCfg && streamCfg.headers) Object.assign(h, streamCfg.headers);
    }
    callback({ requestHeaders: h });
  });

  ses.webRequest.onHeadersReceived((details, callback) => {
    const h = details.responseHeaders || {};
    for (const k of Object.keys(h)) {
      if (k.toLowerCase().startsWith('access-control-allow-')) delete h[k];
    }
    h['Access-Control-Allow-Origin'] = ['*'];
    h['Access-Control-Allow-Headers'] = ['*'];
    callback({ responseHeaders: h });
  });
}

ipcMain.handle('load-playlist', async (_e, url) => {
  const dir = app.getPath('userData');
  const cacheFile = path.join(dir, 'playlist.m3u');
  const cacheUrl = path.join(dir, 'playlist.url');
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 30000);
    const res = await net.fetch(url, { headers: { 'User-Agent': DEFAULT_UA }, signal: ctrl.signal });
    clearTimeout(timer);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const text = await res.text();
    const channels = parseM3U(text);
    if (!channels.length) throw new Error('No channels found in playlist');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(cacheFile, text);
    fs.writeFileSync(cacheUrl, url);
    return { channels, fromCache: false, error: null };
  } catch (err) {
    const msg = (err && err.name === 'AbortError') ? 'Timed out' : String((err && err.message) || err);
    try {
      if (fs.existsSync(cacheFile) && fs.readFileSync(cacheUrl, 'utf8') === url) {
        return { channels: parseM3U(fs.readFileSync(cacheFile, 'utf8')), fromCache: true, error: msg };
      }
    } catch (_) {}
    return { channels: [], fromCache: false, error: msg };
  }
});

ipcMain.handle('set-stream', (_e, cfg) => { streamCfg = cfg || null; });
ipcMain.handle('toggle-fullscreen', () => { if (win) win.setFullScreen(!win.isFullScreen()); });
ipcMain.handle('set-fullscreen', (_e, v) => { if (win) win.setFullScreen(!!v); });
ipcMain.handle('version', () => app.getVersion());

app.whenReady().then(() => {
  setupNetwork();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => app.quit());
