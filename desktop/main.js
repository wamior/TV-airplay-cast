'use strict';

const { app, BrowserWindow, ipcMain } = require('electron');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { Dlna } = require('./src/dlna');
const { Mirror } = require('./src/mirror');

let win = null;
let dlna = null;
let mirror = null;

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');

function loadSettings() {
  let s = {};
  try {
    s = JSON.parse(fs.readFileSync(settingsFile(), 'utf8'));
  } catch (_) { /* first run */ }
  if (!s.uuid) {
    s.uuid = crypto.randomUUID();
    s.name = `TV Cast (${os.hostname()})`;
    fs.mkdirSync(path.dirname(settingsFile()), { recursive: true });
    fs.writeFileSync(settingsFile(), JSON.stringify(s, null, 2));
  }
  return s;
}

function send(channel, ...args) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, ...args);
}

/** Shared playback state, reported by the renderer's <video> element. */
const playback = { state: 'IDLE', position: 0, duration: 0, uri: null, volume: 100, muted: false };

const player = {
  snapshot: () => ({ ...playback }),
  load(url) { showPlayer(); send('cmd', 'load', url); },
  play() { send('cmd', 'play'); },
  pause() { send('cmd', 'pause'); },
  stop() { send('cmd', 'stop'); },
  seek(ms) { send('cmd', 'seek', ms); },
  setVolume(percent) { playback.volume = percent; send('cmd', 'volume', percent); },
  setMute(m) { playback.muted = m; send('cmd', 'mute', m); },
};

function showPlayer() {
  if (!win) return;
  win.show();
  win.setFullScreen(true);
  win.focus();
  send('view', 'player');
}

function showStatus() {
  if (!win) return;
  win.setFullScreen(false);
  send('view', 'status');
}

function createWindow(settings) {
  win = new BrowserWindow({
    width: 900,
    height: 560,
    backgroundColor: '#000000',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      autoplayPolicy: 'no-user-gesture-required',
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.on('did-finish-load', () => send('init', { name: settings.name }));
}

ipcMain.on('state', (_e, s) => Object.assign(playback, s));
ipcMain.on('ended', () => { playback.state = 'STOPPED'; showStatus(); });
ipcMain.on('user-stop', () => {
  playback.state = 'STOPPED';
  if (mirror) mirror.disconnect();
  showStatus();
});

app.whenReady().then(() => {
  const settings = loadSettings();
  createWindow(settings);

  dlna = new Dlna({ uuid: settings.uuid, name: settings.name, player });
  dlna.start().then(
    () => send('status', { dlna: 'listening' }),
    (e) => send('status', { dlna: `error: ${e.message}` }),
  );

  const helper = app.isPackaged
    ? path.join(process.resourcesPath, 'airplay-core.exe')
    : path.join(__dirname, 'core', 'build', 'airplay-core.exe');
  mirror = new Mirror({
    helper,
    name: settings.name,
    uuid: settings.uuid,
    keyFile: path.join(app.getPath('userData'), 'airplay_key'),
    onFrame: (type, payload) => {
      if (type === 'connection' && payload.open) showPlayer();
      if (type === 'connection' && !payload.open) showStatus();
      send('mirror', type, payload);
    },
    onStatus: (s) => send('status', { airplay: s }),
  });
  mirror.start();
});

app.on('before-quit', () => {
  if (mirror) mirror.stop();
  if (dlna) dlna.stop();
});

app.on('window-all-closed', () => app.quit());
