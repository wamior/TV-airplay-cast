'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('tv', {
  on: (channel, fn) => {
    ipcRenderer.on(channel, (_e, ...args) => fn(...args));
  },
  state: (s) => ipcRenderer.send('state', s),
  ended: () => ipcRenderer.send('ended'),
  userStop: () => ipcRenderer.send('user-stop'),
});
