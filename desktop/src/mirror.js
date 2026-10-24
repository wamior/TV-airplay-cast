'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const { Bonjour } = require('bonjour-service');

function parseTxt(text) {
  const txt = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf('=');
    if (i > 0) txt[line.slice(0, i)] = line.slice(i + 1);
  }
  return txt;
}

/**
 * Runs airplay-core.exe (UxPlay protocol library), parses its frame stream and advertises the receiver over mDNS.
 * Frame format is documented in core/airplay_cli.c.
 */
class Mirror {
  constructor({ helper, name, uuid, keyFile, onFrame, onStatus }) {
    this.helper = helper;
    this.name = name;
    this.uuid = uuid;
    this.keyFile = keyFile;
    this.onFrame = onFrame;
    this.onStatus = onStatus;
    this.proc = null;
    this.bonjour = null;
    this.pending = [];
    this.pendingLen = 0;
  }

  start() {
    if (!fs.existsSync(this.helper)) {
      this.onStatus(`helper not found: ${this.helper}`);
      return;
    }
    const mac = this.uuid.replace(/-/g, '').slice(0, 12).toUpperCase().match(/../g).join(':');
    this.proc = spawn(this.helper, [this.name, mac, this.keyFile], { stdio: ['pipe', 'pipe', 'pipe'] });
    this.proc.stderr.on('data', (d) => console.log(String(d).trimEnd()));
    this.proc.stdout.on('data', (chunk) => this.feed(chunk));
    this.proc.on('error', (e) => this.onStatus(`error: ${e.message}`));
    this.proc.on('exit', (code) => this.onStatus(`helper exited (${code})`));
  }

  stop() {
    if (this.proc) {
      try { this.proc.stdin.write('quit\n'); } catch (_) { /* already gone */ }
      setTimeout(() => { try { this.proc.kill(); } catch (_) { /* ignore */ } }, 500);
    }
    if (this.bonjour) {
      try { this.bonjour.unpublishAll(() => this.bonjour.destroy()); } catch (_) { /* ignore */ }
    }
  }

  disconnect() {
    if (this.proc) {
      try { this.proc.stdin.write('disconnect\n'); } catch (_) { /* ignore */ }
    }
  }

  feed(chunk) {
    this.pending.push(chunk);
    this.pendingLen += chunk.length;
    let buf = Buffer.concat(this.pending, this.pendingLen);
    while (buf.length >= 5) {
      const len = buf.readUInt32LE(1);
      if (buf.length < 5 + len) break;
      this.handle(buf[0], buf.subarray(5, 5 + len));
      buf = buf.subarray(5 + len);
    }
    this.pending = buf.length ? [buf] : [];
    this.pendingLen = buf.length;
  }

  handle(type, p) {
    const micros = (off) => Number(p.readBigUInt64LE(off) / 1000n);
    switch (type) {
      case 1:
        this.onFrame('video', { h265: p[0] === 1, ts: micros(1), data: Uint8Array.from(p.subarray(9)) });
        break;
      case 2:
        this.onFrame('videosize', { width: p.readUInt32LE(0), height: p.readUInt32LE(4), h265: p[8] === 1 });
        break;
      case 3:
        this.onFrame('audioformat', { ct: p[0] });
        break;
      case 4:
        this.onFrame('audio', { ct: p[0], ts: micros(1), data: Uint8Array.from(p.subarray(9)) });
        break;
      case 5:
        this.onFrame('flush', { video: p[0] === 1 });
        break;
      case 6:
        this.onFrame('connection', { open: p[0] === 1 });
        break;
      case 7:
        this.onFrame('volume', { db: p.readFloatLE(0) });
        break;
      case 8:
        this.onFrame('reset', { reason: p.readInt32LE(0) });
        break;
      case 9:
        this.publish(p);
        break;
      default:
        console.log('unknown message', type);
    }
  }

  publish(p) {
    const port = p.readUInt16LE(0);
    const [raopName, raopTxt, airplayName, airplayTxt] = p.subarray(2).toString('utf8').split('\0');
    this.bonjour = new Bonjour();
    this.bonjour.publish({ name: raopName, type: 'raop', port, txt: parseTxt(raopTxt) });
    this.bonjour.publish({ name: airplayName, type: 'airplay', port, txt: parseTxt(airplayTxt) });
    this.onStatus('screen mirroring');
  }
}

module.exports = { Mirror };
