'use strict';

const $ = (id) => document.getElementById(id);
const video = $('video');
const canvas = $('canvas');
const g2d = canvas.getContext('2d');

// ---- status / view switching ----

tv.on('init', ({ name }) => { $('title').textContent = name; });
tv.on('status', (s) => {
  if (s.airplay) $('s-airplay').textContent = s.airplay;
  if (s.dlna) $('s-dlna').textContent = s.dlna;
});
tv.on('view', (v) => {
  document.body.classList.toggle('playing', v === 'player');
  if (v !== 'player') {
    video.removeAttribute('src');
    video.load();
    stopMirror();
  }
});

window.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    video.pause();
    tv.userStop();
  } else if (e.key === ' ' && !document.body.classList.contains('mirror')) {
    if (video.paused) video.play(); else video.pause();
  }
});

// ---- DLNA playback (<video>) ----

let currentUri = null;

function report(state) {
  tv.state({
    state,
    position: Math.round((video.currentTime || 0) * 1000),
    duration: Number.isFinite(video.duration) ? Math.round(video.duration * 1000) : 0,
    uri: currentUri,
  });
}

video.addEventListener('playing', () => report('PLAYING'));
video.addEventListener('pause', () => { if (!video.ended) report('PAUSED'); });
video.addEventListener('waiting', () => report('LOADING'));
video.addEventListener('timeupdate', () => report(video.paused ? 'PAUSED' : 'PLAYING'));
video.addEventListener('ended', () => { report('STOPPED'); tv.ended(); });
video.addEventListener('error', () => { report('STOPPED'); tv.ended(); });

tv.on('cmd', (cmd, arg) => {
  document.body.classList.remove('mirror');
  switch (cmd) {
    case 'load':
      currentUri = arg;
      video.src = arg;
      report('LOADING');
      video.play().catch((e) => console.log('play failed', e));
      break;
    case 'play': video.play(); break;
    case 'pause': video.pause(); break;
    case 'stop':
      video.pause();
      report('STOPPED');
      tv.ended();
      break;
    case 'seek': video.currentTime = arg / 1000; break;
    case 'volume': video.volume = Math.min(1, Math.max(0, arg / 100)); break;
    case 'mute': video.muted = !!arg; break;
    default: break;
  }
});

// ---- AirPlay mirroring (WebCodecs) ----

let vdec = null;
let vdecCodec = null;
let needKey = true;
let spsPps = null;
let adec = null;
let actx = null;
let gain = null;
let nextAudioTime = 0;

function nalTypes(data) {
  const types = [];
  for (let i = 0; i + 4 < data.length; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && (data[i + 2] === 1 || (data[i + 2] === 0 && data[i + 3] === 1))) {
      const start = data[i + 2] === 1 ? i + 3 : i + 4;
      types.push({ type: data[start] & 0x1f, start });
      i = start;
    }
  }
  return types;
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

function configureVideo(codec) {
  if (vdec) { try { vdec.close(); } catch (_) { /* closed */ } }
  vdecCodec = codec;
  vdec = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      g2d.drawImage(frame, 0, 0);
      frame.close();
    },
    error: (e) => { console.log('video decoder error', e); vdec = null; needKey = true; },
  });
  vdec.configure({ codec, optimizeForLatency: true, avc: { format: 'annexb' } });
  needKey = true;
}

function onVideo({ h265, ts, data }) {
  if (h265 || typeof VideoDecoder === 'undefined') return;
  const nals = nalTypes(data);
  const sps = nals.find((n) => n.type === 7);
  if (sps) {
    const hex = (n) => n.toString(16).padStart(2, '0');
    const codec = `avc1.${hex(data[sps.start + 1])}${hex(data[sps.start + 2])}${hex(data[sps.start + 3])}`;
    spsPps = data;
    if (!vdec || codec !== vdecCodec) configureVideo(codec);
  }
  const idr = nals.some((n) => n.type === 5);
  const slice = idr || nals.some((n) => n.type === 1);
  if (!slice || !vdec) return;
  if (needKey && !idr) return;
  let payload = data;
  if (idr && !sps && spsPps) payload = concat(spsPps, data);
  needKey = false;
  try {
    vdec.decode(new EncodedVideoChunk({ type: idr ? 'key' : 'delta', timestamp: ts, data: payload }));
  } catch (e) {
    console.log('decode failed', e);
    needKey = true;
  }
}

async function setupAudio(ct) {
  if (adec) { try { adec.close(); } catch (_) { /* closed */ } adec = null; }
  if (typeof AudioDecoder === 'undefined') return;
  // ct: 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD
  const codec = ct === 8 ? 'mp4a.40.39' : ct === 4 ? 'mp4a.40.2' : null;
  if (!codec) { console.log('audio codec not supported', ct); return; }
  const description = ct === 8 ? new Uint8Array([0xf8, 0xe8, 0x50, 0x00]) : new Uint8Array([0x12, 0x10]);
  const cfg = { codec, sampleRate: 44100, numberOfChannels: 2, description };
  const sup = await AudioDecoder.isConfigSupported(cfg).catch(() => ({ supported: false }));
  if (!sup.supported) { console.log('audio decoder unavailable for', codec); return; }
  if (!actx) {
    actx = new AudioContext({ latencyHint: 'interactive', sampleRate: 44100 });
    gain = actx.createGain();
    gain.connect(actx.destination);
  }
  actx.resume();
  nextAudioTime = 0;
  adec = new AudioDecoder({ output: playAudio, error: (e) => console.log('audio decoder error', e) });
  adec.configure(cfg);
}

function playAudio(data) {
  const frames = data.numberOfFrames;
  const buf = actx.createBuffer(data.numberOfChannels, frames, data.sampleRate);
  for (let c = 0; c < data.numberOfChannels; c++) {
    const plane = new Float32Array(frames);
    data.copyTo(plane, { planeIndex: c, format: 'f32-planar' });
    buf.copyToChannel(plane, c);
  }
  data.close();
  const src = actx.createBufferSource();
  src.buffer = buf;
  src.connect(gain);
  const when = Math.max(actx.currentTime + 0.04, nextAudioTime);
  src.start(when);
  nextAudioTime = when + buf.duration;
}

function onAudio({ ts, data }) {
  if (!adec) return;
  try {
    adec.decode(new EncodedAudioChunk({ type: 'key', timestamp: ts, data }));
  } catch (e) {
    console.log('audio decode failed', e);
  }
}

function stopMirror() {
  if (vdec) { try { vdec.close(); } catch (_) { /* closed */ } vdec = null; }
  if (adec) { try { adec.close(); } catch (_) { /* closed */ } adec = null; }
  vdecCodec = null;
  spsPps = null;
  needKey = true;
  g2d.clearRect(0, 0, canvas.width, canvas.height);
}

tv.on('mirror', (type, p) => {
  switch (type) {
    case 'connection':
      if (p.open) document.body.classList.add('mirror');
      else stopMirror();
      break;
    case 'videosize': needKey = true; break;
    case 'video': document.body.classList.add('mirror'); onVideo(p); break;
    case 'audioformat': setupAudio(p.ct); break;
    case 'audio': onAudio(p); break;
    case 'flush': if (p.video) needKey = true; else nextAudioTime = 0; break;
    case 'volume':
      if (gain) gain.gain.value = p.db <= -30 ? 0 : p.db >= 0 ? 1 : (30 + p.db) / 30;
      break;
    case 'reset': stopMirror(); break;
    default: break;
  }
});
