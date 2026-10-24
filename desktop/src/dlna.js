'use strict';

const dgram = require('dgram');
const http = require('http');
const os = require('os');
const crypto = require('crypto');

const SSDP_ADDR = '239.255.255.250';
const SSDP_PORT = 1900;
const HTTP_PORT = 49152;
const DEVICE_TYPE = 'urn:schemas-upnp-org:device:MediaRenderer:1';

const a = (dir, name, sv) => ({ dir, name, sv });
const IN = (n, sv) => a('in', n, sv);
const OUT = (n, sv) => a('out', n, sv);
const INST = IN('InstanceID', 'A_ARG_TYPE_InstanceID');
const sv = (name, type = 'string', extra = {}) => ({ name, type, ...extra });

const SERVICES = [
  {
    short: 'AVTransport',
    type: 'urn:schemas-upnp-org:service:AVTransport:1',
    id: 'urn:upnp-org:serviceId:AVTransport',
    actions: {
      SetAVTransportURI: [INST, IN('CurrentURI', 'AVTransportURI'), IN('CurrentURIMetaData', 'AVTransportURIMetaData')],
      GetMediaInfo: [INST, OUT('NrTracks', 'NumberOfTracks'), OUT('MediaDuration', 'CurrentMediaDuration'),
        OUT('CurrentURI', 'AVTransportURI'), OUT('CurrentURIMetaData', 'AVTransportURIMetaData'),
        OUT('NextURI', 'NextAVTransportURI'), OUT('NextURIMetaData', 'NextAVTransportURIMetaData'),
        OUT('PlayMedium', 'PlaybackStorageMedium'), OUT('RecordMedium', 'RecordStorageMedium'),
        OUT('WriteStatus', 'RecordMediumWriteStatus')],
      GetTransportInfo: [INST, OUT('CurrentTransportState', 'TransportState'),
        OUT('CurrentTransportStatus', 'TransportStatus'), OUT('CurrentSpeed', 'TransportPlaySpeed')],
      GetPositionInfo: [INST, OUT('Track', 'CurrentTrack'), OUT('TrackDuration', 'CurrentTrackDuration'),
        OUT('TrackMetaData', 'CurrentTrackMetaData'), OUT('TrackURI', 'CurrentTrackURI'),
        OUT('RelTime', 'RelativeTimePosition'), OUT('AbsTime', 'AbsoluteTimePosition'),
        OUT('RelCount', 'RelativeCounterPosition'), OUT('AbsCount', 'AbsoluteCounterPosition')],
      GetDeviceCapabilities: [INST, OUT('PlayMedia', 'PossiblePlaybackStorageMedia'),
        OUT('RecMedia', 'PossibleRecordStorageMedia'), OUT('RecQualityModes', 'PossibleRecordQualityModes')],
      GetTransportSettings: [INST, OUT('PlayMode', 'CurrentPlayMode'), OUT('RecQualityMode', 'CurrentRecordQualityMode')],
      Stop: [INST],
      Play: [INST, IN('Speed', 'TransportPlaySpeed')],
      Pause: [INST],
      Seek: [INST, IN('Unit', 'A_ARG_TYPE_SeekMode'), IN('Target', 'A_ARG_TYPE_SeekTarget')],
      Next: [INST],
      Previous: [INST],
      GetCurrentTransportActions: [INST, OUT('Actions', 'CurrentTransportActions')],
    },
    vars: [
      sv('A_ARG_TYPE_InstanceID', 'ui4'), sv('AVTransportURI'), sv('AVTransportURIMetaData'),
      sv('NextAVTransportURI'), sv('NextAVTransportURIMetaData'), sv('NumberOfTracks', 'ui4'),
      sv('CurrentMediaDuration'), sv('PlaybackStorageMedium'), sv('RecordStorageMedium'),
      sv('PossiblePlaybackStorageMedia'), sv('PossibleRecordStorageMedia'), sv('RecordMediumWriteStatus'),
      sv('TransportState', 'string', { allowed: ['STOPPED', 'PLAYING', 'PAUSED_PLAYBACK', 'TRANSITIONING', 'NO_MEDIA_PRESENT'] }),
      sv('TransportStatus', 'string', { allowed: ['OK', 'ERROR_OCCURRED'] }),
      sv('TransportPlaySpeed', 'string', { allowed: ['1'] }),
      sv('CurrentPlayMode', 'string', { allowed: ['NORMAL'] }),
      sv('CurrentRecordQualityMode'), sv('PossibleRecordQualityModes'),
      sv('CurrentTrack', 'ui4'), sv('CurrentTrackDuration'), sv('CurrentTrackMetaData'), sv('CurrentTrackURI'),
      sv('RelativeTimePosition'), sv('AbsoluteTimePosition'),
      sv('RelativeCounterPosition', 'i4'), sv('AbsoluteCounterPosition', 'ui4'),
      sv('CurrentTransportActions'),
      sv('A_ARG_TYPE_SeekMode', 'string', { allowed: ['REL_TIME', 'ABS_TIME', 'TRACK_NR'] }),
      sv('A_ARG_TYPE_SeekTarget'), sv('LastChange', 'string', { events: true }),
    ],
  },
  {
    short: 'RenderingControl',
    type: 'urn:schemas-upnp-org:service:RenderingControl:1',
    id: 'urn:upnp-org:serviceId:RenderingControl',
    actions: {
      GetVolume: [INST, IN('Channel', 'A_ARG_TYPE_Channel'), OUT('CurrentVolume', 'Volume')],
      SetVolume: [INST, IN('Channel', 'A_ARG_TYPE_Channel'), IN('DesiredVolume', 'Volume')],
      GetMute: [INST, IN('Channel', 'A_ARG_TYPE_Channel'), OUT('CurrentMute', 'Mute')],
      SetMute: [INST, IN('Channel', 'A_ARG_TYPE_Channel'), IN('DesiredMute', 'Mute')],
    },
    vars: [
      sv('A_ARG_TYPE_InstanceID', 'ui4'),
      sv('A_ARG_TYPE_Channel', 'string', { allowed: ['Master'] }),
      sv('Volume', 'ui2', { range: [0, 100] }), sv('Mute', 'boolean'),
      sv('LastChange', 'string', { events: true }),
    ],
  },
  {
    short: 'ConnectionManager',
    type: 'urn:schemas-upnp-org:service:ConnectionManager:1',
    id: 'urn:upnp-org:serviceId:ConnectionManager',
    actions: {
      GetProtocolInfo: [OUT('Source', 'SourceProtocolInfo'), OUT('Sink', 'SinkProtocolInfo')],
      GetCurrentConnectionIDs: [OUT('ConnectionIDs', 'CurrentConnectionIDs')],
      GetCurrentConnectionInfo: [IN('ConnectionID', 'A_ARG_TYPE_ConnectionID'), OUT('RcsID', 'A_ARG_TYPE_RcsID'),
        OUT('AVTransportID', 'A_ARG_TYPE_AVTransportID'), OUT('ProtocolInfo', 'A_ARG_TYPE_ProtocolInfo'),
        OUT('PeerConnectionManager', 'A_ARG_TYPE_ConnectionManager'), OUT('PeerConnectionID', 'A_ARG_TYPE_ConnectionID'),
        OUT('Direction', 'A_ARG_TYPE_Direction'), OUT('Status', 'A_ARG_TYPE_ConnectionStatus')],
    },
    vars: [
      sv('SourceProtocolInfo', 'string', { events: true }), sv('SinkProtocolInfo', 'string', { events: true }),
      sv('CurrentConnectionIDs', 'string', { events: true }), sv('A_ARG_TYPE_ConnectionStatus'),
      sv('A_ARG_TYPE_ConnectionManager'), sv('A_ARG_TYPE_Direction'), sv('A_ARG_TYPE_ProtocolInfo'),
      sv('A_ARG_TYPE_ConnectionID', 'i4'), sv('A_ARG_TYPE_AVTransportID', 'i4'), sv('A_ARG_TYPE_RcsID', 'i4'),
    ],
  },
];

const SINK_PROTOCOLS = [
  'video/mp4', 'video/mpeg', 'video/x-matroska', 'video/x-msvideo', 'video/webm', 'video/quicktime',
  'audio/mpeg', 'audio/mp4', 'audio/flac', 'audio/x-wav', 'application/vnd.apple.mpegurl', 'image/jpeg',
].map((m) => `http-get:*:${m}:*`).join(',');

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const unesc = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');

function scpd(svc) {
  let x = '<?xml version="1.0"?>\n<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>';
  for (const [name, args] of Object.entries(svc.actions)) {
    x += `<action><name>${name}</name>`;
    if (args.length) {
      x += '<argumentList>';
      for (const g of args) {
        x += `<argument><name>${g.name}</name><direction>${g.dir}</direction><relatedStateVariable>${g.sv}</relatedStateVariable></argument>`;
      }
      x += '</argumentList>';
    }
    x += '</action>';
  }
  x += '</actionList><serviceStateTable>';
  for (const v of svc.vars) {
    x += `<stateVariable sendEvents="${v.events ? 'yes' : 'no'}"><name>${v.name}</name><dataType>${v.type}</dataType>`;
    if (v.allowed) x += `<allowedValueList>${v.allowed.map((t) => `<allowedValue>${t}</allowedValue>`).join('')}</allowedValueList>`;
    if (v.range) x += `<allowedValueRange><minimum>${v.range[0]}</minimum><maximum>${v.range[1]}</maximum><step>1</step></allowedValueRange>`;
    x += '</stateVariable>';
  }
  return `${x}</serviceStateTable></scpd>`;
}

function fmtTime(ms) {
  const t = Math.floor(ms / 1000);
  const p = (n) => String(n).padStart(2, '0');
  return `${Math.floor(t / 3600)}:${p(Math.floor(t / 60) % 60)}:${p(t % 60)}`;
}

function parseTime(s) {
  const parts = s.split(':');
  if (parts.length !== 3) return 0;
  return Math.round((Number(parts[0]) * 3600 + Number(parts[1]) * 60 + Number(parts[2])) * 1000) || 0;
}

function parseSoap(body) {
  const m = /<(?:\w+:)?Body[^>]*>\s*<(?:\w+:)?(\w+)[^>]*?(?:\/>|>([\s\S]*?)<\/(?:\w+:)?\1>)/.exec(body);
  const args = {};
  if (!m || !m[2]) return args;
  const re = /<(\w+)(?:\s[^>]*)?>([\s\S]*?)<\/\1>|<(\w+)\s*\/>/g;
  let c;
  while ((c = re.exec(m[2]))) {
    if (c[1]) args[c[1]] = unesc(c[2]);
    else args[c[3]] = '';
  }
  return args;
}

class UpnpError extends Error {
  constructor(code, description) {
    super(description);
    this.code = code;
  }
}

class Dlna {
  constructor({ uuid, name, player }) {
    this.uuid = uuid;
    this.name = name;
    this.player = player;
    this.uri = null;
    this.meta = '';
    this.loadedUri = null;
  }

  async start() {
    this.server = http.createServer((req, res) => this.handle(req, res));
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(HTTP_PORT, '0.0.0.0', resolve);
    });
    this.startSsdp();
  }

  stop() {
    try { this.notifyAll('ssdp:byebye'); } catch (_) { /* ignore */ }
    if (this.timer) clearInterval(this.timer);
    if (this.sock) this.sock.close();
    if (this.server) this.server.close();
  }

  // ---- SSDP ----

  targets() {
    const u = `uuid:${this.uuid}`;
    return [
      ['upnp:rootdevice', `${u}::upnp:rootdevice`],
      [u, u],
      [DEVICE_TYPE, `${u}::${DEVICE_TYPE}`],
      ...SERVICES.map((s) => [s.type, `${u}::${s.type}`]),
    ];
  }

  interfaces() {
    const out = [];
    for (const list of Object.values(os.networkInterfaces())) {
      for (const i of list || []) if (i.family === 'IPv4' && !i.internal) out.push(i);
    }
    return out;
  }

  ipFor(remote) {
    const ifs = this.interfaces();
    const toInt = (ip) => ip.split('.').reduce((acc, o) => (acc << 8) + Number(o), 0) >>> 0;
    const r = toInt(remote);
    const hit = ifs.find((i) => (toInt(i.address) & toInt(i.netmask)) === (r & toInt(i.netmask)));
    return (hit || ifs[0] || { address: '127.0.0.1' }).address;
  }

  startSsdp() {
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sock = sock;
    sock.on('error', (e) => console.log('ssdp error', e.message));
    sock.on('message', (msg, rinfo) => {
      const text = msg.toString('utf8');
      if (!text.startsWith('M-SEARCH')) return;
      const st = /^ST:\s*(.+)$/im.exec(text);
      if (!st) return;
      const want = st[1].trim();
      const loc = `http://${this.ipFor(rinfo.address)}:${HTTP_PORT}/desc.xml`;
      for (const [t, usn] of this.targets()) {
        if (want !== 'ssdp:all' && want !== t) continue;
        const reply = 'HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n' +
          `LOCATION: ${loc}\r\nSERVER: ${os.type()} UPnP/1.0 TVCast/0.2\r\nST: ${t}\r\nUSN: ${usn}\r\n\r\n`;
        setTimeout(() => sock.send(reply, rinfo.port, rinfo.address), Math.random() * 300);
      }
    });
    sock.bind(SSDP_PORT, '0.0.0.0', () => {
      for (const i of this.interfaces()) {
        try { sock.addMembership(SSDP_ADDR, i.address); } catch (e) { console.log('membership', e.message); }
      }
      sock.setMulticastTTL(4);
      this.notifyAll('ssdp:alive');
      setTimeout(() => this.notifyAll('ssdp:alive'), 2000);
      this.timer = setInterval(() => this.notifyAll('ssdp:alive'), 300000);
    });
  }

  notifyAll(nts) {
    if (!this.sock) return;
    for (const i of this.interfaces()) {
      const loc = `http://${i.address}:${HTTP_PORT}/desc.xml`;
      try { this.sock.setMulticastInterface(i.address); } catch (_) { continue; }
      for (const [t, usn] of this.targets()) {
        const msg = `NOTIFY * HTTP/1.1\r\nHOST: ${SSDP_ADDR}:${SSDP_PORT}\r\nCACHE-CONTROL: max-age=1800\r\n` +
          `LOCATION: ${loc}\r\nNT: ${t}\r\nNTS: ${nts}\r\nSERVER: ${os.type()} UPnP/1.0 TVCast/0.2\r\nUSN: ${usn}\r\n\r\n`;
        this.sock.send(msg, SSDP_PORT, SSDP_ADDR);
      }
    }
  }

  // ---- HTTP / SOAP ----

  description() {
    let x = '<?xml version="1.0"?>\n<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>';
    x += `<deviceType>${DEVICE_TYPE}</deviceType><friendlyName>${esc(this.name)}</friendlyName>`;
    x += '<manufacturer>wamior</manufacturer><modelName>TV AirPlay Cast</modelName><modelNumber>1</modelNumber>';
    x += `<UDN>uuid:${this.uuid}</UDN><serviceList>`;
    for (const s of SERVICES) {
      x += `<service><serviceType>${s.type}</serviceType><serviceId>${s.id}</serviceId>` +
        `<SCPDURL>/${s.short}/scpd.xml</SCPDURL><controlURL>/${s.short}/control</controlURL>` +
        `<eventSubURL>/${s.short}/event</eventSubURL></service>`;
    }
    return `${x}</serviceList></device></root>`;
  }

  handle(req, res) {
    const send = (code, body, headers = {}) => {
      res.writeHead(code, { 'Content-Type': 'text/xml; charset="utf-8"', ...headers });
      res.end(body);
    };
    const path = req.url.split('?')[0];
    if (path === '/desc.xml') return send(200, this.description());
    const svc = SERVICES.find((s) => path.startsWith(`/${s.short}/`));
    if (!svc) return send(404, '');
    if (path.endsWith('/scpd.xml')) return send(200, scpd(svc));
    if (path.endsWith('/event')) {
      if (req.method === 'SUBSCRIBE') return send(200, '', { SID: `uuid:${crypto.randomUUID()}`, TIMEOUT: 'Second-1800' });
      return send(200, '');
    }
    if (path.endsWith('/control') && req.method === 'POST') {
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => this.control(svc, req, Buffer.concat(chunks).toString('utf8'), send));
      return undefined;
    }
    return send(405, '');
  }

  control(svc, req, body, send) {
    const action = (req.headers.soapaction || '').replace(/"/g, '').split('#')[1];
    try {
      const args = parseSoap(body);
      let out;
      if (svc.short === 'AVTransport') out = this.avTransport(action, args);
      else if (svc.short === 'RenderingControl') out = this.rendering(action, args);
      else out = this.connection(action);
      let x = '<?xml version="1.0"?>\n<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>';
      x += `<u:${action}Response xmlns:u="${svc.type}">`;
      for (const [k, v] of Object.entries(out)) x += `<${k}>${esc(v)}</${k}>`;
      send(200, `${x}</u:${action}Response></s:Body></s:Envelope>`);
    } catch (e) {
      const code = e instanceof UpnpError ? e.code : 501;
      const desc = e instanceof UpnpError ? e.message : 'Action Failed';
      if (!(e instanceof UpnpError)) console.log('control error', e);
      send(500, '<?xml version="1.0"?>\n<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>' +
        '<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>' +
        `<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>${code}</errorCode><errorDescription>${esc(desc)}</errorDescription></UPnPError>` +
        '</detail></s:Fault></s:Body></s:Envelope>');
    }
  }

  avTransport(action, a) {
    const snap = this.player.snapshot();
    switch (action) {
      case 'SetAVTransportURI':
        this.uri = a.CurrentURI;
        this.meta = a.CurrentURIMetaData || '';
        return {};
      case 'Play':
        if (!this.uri) throw new UpnpError(701, 'Transition not available');
        if (this.uri !== this.loadedUri || snap.state === 'IDLE' || snap.state === 'STOPPED') {
          this.loadedUri = this.uri;
          this.player.load(this.uri);
        } else {
          this.player.play();
        }
        return {};
      case 'Pause': this.player.pause(); return {};
      case 'Stop': this.player.stop(); this.loadedUri = null; return {};
      case 'Seek':
        if (a.Unit === 'REL_TIME' || a.Unit === 'ABS_TIME') this.player.seek(parseTime(a.Target || ''));
        return {};
      case 'Next':
      case 'Previous':
        return {};
      case 'GetTransportInfo':
        return {
          CurrentTransportState: { PLAYING: 'PLAYING', PAUSED: 'PAUSED_PLAYBACK', LOADING: 'TRANSITIONING', STOPPED: 'STOPPED' }[snap.state] ||
            (this.uri ? 'STOPPED' : 'NO_MEDIA_PRESENT'),
          CurrentTransportStatus: 'OK',
          CurrentSpeed: '1',
        };
      case 'GetPositionInfo':
        return {
          Track: '1', TrackDuration: fmtTime(snap.duration), TrackMetaData: this.meta, TrackURI: this.uri || '',
          RelTime: fmtTime(snap.position), AbsTime: fmtTime(snap.position), RelCount: '2147483647', AbsCount: '2147483647',
        };
      case 'GetMediaInfo':
        return {
          NrTracks: this.uri ? '1' : '0', MediaDuration: fmtTime(snap.duration), CurrentURI: this.uri || '',
          CurrentURIMetaData: this.meta, NextURI: 'NOT_IMPLEMENTED', NextURIMetaData: 'NOT_IMPLEMENTED',
          PlayMedium: 'NETWORK', RecordMedium: 'NOT_IMPLEMENTED', WriteStatus: 'NOT_IMPLEMENTED',
        };
      case 'GetDeviceCapabilities':
        return { PlayMedia: 'NETWORK', RecMedia: 'NOT_IMPLEMENTED', RecQualityModes: 'NOT_IMPLEMENTED' };
      case 'GetTransportSettings':
        return { PlayMode: 'NORMAL', RecQualityMode: 'NOT_IMPLEMENTED' };
      case 'GetCurrentTransportActions':
        return { Actions: 'Play,Pause,Stop,Seek' };
      default:
        throw new UpnpError(401, 'Invalid Action');
    }
  }

  rendering(action, a) {
    const snap = this.player.snapshot();
    switch (action) {
      case 'GetVolume': return { CurrentVolume: String(snap.volume) };
      case 'SetVolume': this.player.setVolume(Number(a.DesiredVolume) || 0); return {};
      case 'GetMute': return { CurrentMute: snap.muted ? '1' : '0' };
      case 'SetMute': this.player.setMute(a.DesiredMute === '1' || /^true$/i.test(a.DesiredMute)); return {};
      default: throw new UpnpError(401, 'Invalid Action');
    }
  }

  connection(action) {
    switch (action) {
      case 'GetProtocolInfo': return { Source: '', Sink: SINK_PROTOCOLS };
      case 'GetCurrentConnectionIDs': return { ConnectionIDs: '0' };
      case 'GetCurrentConnectionInfo':
        return { RcsID: '0', AVTransportID: '0', ProtocolInfo: '', PeerConnectionManager: '', PeerConnectionID: '-1', Direction: 'Input', Status: 'OK' };
      default: throw new UpnpError(401, 'Invalid Action');
    }
  }
}

module.exports = { Dlna, parseSoap, fmtTime, parseTime };
