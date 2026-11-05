'use strict';
/**
 * BITS Tandem Centre - lightweight WebSocket signaling server.
 * Developed by Blind Indian Tech Support. Lead Developer: Aman.
 *
 * It only relays WebRTC session descriptions / ICE candidates between two
 * devices that share the same Meeting ID. No screen data passes through here.
 */
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 10000;
const ID_RE = /^\d{7}[A-Z]$/;          // 7 digits + 1 uppercase letter
const ROOM_TTL_MS = 30 * 60 * 1000;    // unused waiting rooms expire after 30 min
const MAX_FAILS = 10;                  // failed joins per IP per minute
const startedAt = Date.now();

/** id -> { controller: ws, sharer: ws|null, created: number } */
const rooms = new Map();
/** ip -> { count, reset } */
const failures = new Map();

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (url === '/' || url === '/health') {
    const body = JSON.stringify({
      status: 'ok',
      service: 'BITS Tandem Centre signaling',
      rooms: rooms.size,
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
    });
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(req.method === 'HEAD' ? undefined : body);
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

const wss = new WebSocketServer({ server, maxPayload: 64 * 1024 });

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (typeof fwd === 'string' && fwd.length > 0) return fwd.split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

function tooManyFailures(ip) {
  const now = Date.now();
  const f = failures.get(ip);
  return !!f && f.reset > now && f.count >= MAX_FAILS;
}

function recordFailure(ip) {
  const now = Date.now();
  const f = failures.get(ip);
  if (!f || f.reset <= now) failures.set(ip, { count: 1, reset: now + 60000 });
  else f.count += 1;
}

function leave(ws) {
  const id = ws.roomId;
  if (!id) return;
  const room = rooms.get(id);
  ws.roomId = null;
  if (!room) return;

  if (ws.role === 'controller' && room.controller === ws) {
    if (room.sharer) {
      send(room.sharer, { type: 'peer-left' });
      room.sharer.roomId = null;
    }
    rooms.delete(id);
  } else if (ws.role === 'sharer' && room.sharer === ws) {
    room.sharer = null;
    send(room.controller, { type: 'peer-left' });
  }
}

function handle(ws, m) {
  if (!m || typeof m.type !== 'string') {
    send(ws, { type: 'error', code: 'bad-message' });
    return;
  }

  switch (m.type) {
    case 'create': {
      if (ws.roomId) return send(ws, { type: 'error', code: 'already-in-meeting' });
      if (typeof m.id !== 'string' || !ID_RE.test(m.id)) {
        return send(ws, { type: 'error', code: 'bad-id' });
      }
      if (rooms.has(m.id)) return send(ws, { type: 'error', code: 'id-in-use' });
      rooms.set(m.id, { controller: ws, sharer: null, created: Date.now() });
      ws.roomId = m.id;
      ws.role = 'controller';
      send(ws, { type: 'created', id: m.id });
      break;
    }

    case 'join': {
      if (ws.roomId) return send(ws, { type: 'error', code: 'already-in-meeting' });
      if (tooManyFailures(ws.ip)) return send(ws, { type: 'error', code: 'rate-limited' });
      if (typeof m.id !== 'string' || !ID_RE.test(m.id)) {
        recordFailure(ws.ip);
        return send(ws, { type: 'error', code: 'bad-id' });
      }
      const room = rooms.get(m.id);
      if (!room) {
        recordFailure(ws.ip);
        return send(ws, { type: 'error', code: 'no-such-meeting' });
      }
      if (room.sharer) return send(ws, { type: 'error', code: 'meeting-busy' });
      room.sharer = ws;
      ws.roomId = m.id;
      ws.role = 'sharer';
      send(ws, { type: 'joined', id: m.id });
      send(room.controller, { type: 'peer-joined' });
      send(ws, { type: 'peer-joined' });
      break;
    }

    case 'signal': {
      const room = ws.roomId && rooms.get(ws.roomId);
      if (!room) return send(ws, { type: 'error', code: 'not-in-meeting' });
      const peer = ws.role === 'controller' ? room.sharer : room.controller;
      send(peer, { type: 'signal', data: m.data });
      break;
    }

    case 'leave':
      leave(ws);
      break;

    default:
      send(ws, { type: 'error', code: 'unknown-type' });
  }
}

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.ip = clientIp(req);
  ws.roomId = null;
  ws.role = null;

  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      send(ws, { type: 'error', code: 'bad-json' });
      return;
    }
    handle(ws, msg);
  });
  ws.on('close', () => leave(ws));
  ws.on('error', () => { /* handled by close */ });
});

// Heartbeat: drop dead sockets so rooms never get stuck.
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) { ws.terminate(); return; }
    ws.isAlive = false;
    try { ws.ping(); } catch (e) { /* ignore */ }
  });
}, 25000);

// Expire rooms nobody joined, and old failure counters.
const sweeper = setInterval(() => {
  const now = Date.now();
  for (const [id, room] of rooms) {
    if (!room.sharer && now - room.created > ROOM_TTL_MS) {
      send(room.controller, { type: 'error', code: 'meeting-expired' });
      room.controller.roomId = null;
      rooms.delete(id);
    }
  }
  for (const [ip, f] of failures) {
    if (f.reset <= now) failures.delete(ip);
  }
}, 60000);

server.listen(PORT, '0.0.0.0', () => {
  console.log(`BITS Tandem Centre signaling server listening on port ${PORT}`);
});

function shutdown() {
  clearInterval(heartbeat);
  clearInterval(sweeper);
  wss.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
