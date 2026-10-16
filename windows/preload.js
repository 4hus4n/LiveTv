'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  loadPlaylist: (url) => ipcRenderer.invoke('load-playlist', url),
  setStream: (cfg) => ipcRenderer.invoke('set-stream', cfg),
  toggleFullscreen: () => ipcRenderer.invoke('toggle-fullscreen'),
  setFullscreen: (v) => ipcRenderer.invoke('set-fullscreen', v),
  version: () => ipcRenderer.invoke('version'),
  onFullscreen: (cb) => ipcRenderer.on('fullscreen', (_e, v) => cb(v)),
});
