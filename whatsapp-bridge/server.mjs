import http from 'node:http';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import pino from 'pino';
import makeWASocket, { DisconnectReason, useMultiFileAuthState } from '@whiskeysockets/baileys';
import { Inbox, incomingText, normalizePhone, ownerJid } from './queue.mjs';

const root = new URL('.', import.meta.url).pathname;
const config = JSON.parse(readFileSync(join(root, 'config.json'), 'utf8'));
const ownerPhone = normalizePhone(config.ownerPhone);
const secret = config.apiKey;
if (ownerPhone.length < 8 || !/^[a-f0-9]{64}$/.test(secret)) {
  throw new Error('Invalid config.json: ownerPhone and 64-character apiKey are required. Run install-termux.sh.');
}
const host = '127.0.0.1';
const port = Number(config.port ?? 3000);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid port');
const logger = pino({ level: process.env.LOG_LEVEL ?? 'info' });
const inbox = new Inbox(join(root, 'data', 'inbox.jsonl'));
const sentIds = new Set();
const pendingReplyBodies = new Map();
const sendResults = new Map();
let socket;
let connection = 'starting';
let retryTimer;
let retryNumber = 0;
let pairingRequested = false;
let connectingGeneration = 0;

function scheduleReconnect() {
  const wait = Math.min(60_000, 1_000 * 2 ** Math.min(retryNumber++, 6)) + Math.floor(Math.random() * 1000);
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => connect().catch(error => {
    connection = 'disconnected';
    logger.error({ error }, 'Reconnect failed');
    scheduleReconnect();
  }), wait);
}

function safeEqual(provided) {
  if (typeof provided !== 'string') return false;
  const a = Buffer.from(provided);
  const b = Buffer.from(secret);
  return a.length === b.length && timingSafeEqual(a, b);
}

function json(res, status, payload) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(payload));
}

async function bodyJson(req) {
  const pieces = [];
  let size = 0;
  for await (const piece of req) {
    size += piece.length;
    if (size > 16 * 1024) throw Object.assign(new Error('Request too large'), { status: 413 });
    pieces.push(piece);
  }
  try { return JSON.parse(Buffer.concat(pieces).toString('utf8')); }
  catch { throw Object.assign(new Error('Invalid JSON'), { status: 400 }); }
}

async function connect() {
  const generation = ++connectingGeneration;
  pairingRequested = false;
  const { state, saveCreds } = await useMultiFileAuthState(join(root, 'auth'));
  const next = makeWASocket({
    auth: state,
    logger,
    printQRInTerminal: false,
    syncFullHistory: false,
    shouldSyncHistoryMessage: () => false,
    markOnlineOnConnect: false
  });
  socket = next;
  connection = 'connecting';
  next.ev.on('creds.update', saveCreds);
  next.ev.on('messages.upsert', ({ messages, type, requestId }) => {
    if (generation !== connectingGeneration || type !== 'notify' || requestId) return;
    for (const message of messages) {
      const eligible = incomingText(message, ownerPhone, sentIds);
      if (eligible && !(eligible.fromMe && (pendingReplyBodies.get(eligible.body) ?? 0) > Date.now())) {
        try { inbox.add(eligible); }
        catch (error) { logger.error({ error }, 'Failed to persist incoming message'); }
      }
    }
  });
  next.ev.on('connection.update', ({ connection: stateName, lastDisconnect }) => {
    if (generation !== connectingGeneration) return;
    if (stateName === 'open') {
      connection = 'open';
      retryNumber = 0;
      pairingRequested = false;
      logger.info('WhatsApp connected');
    } else if (stateName === 'connecting') {
      connection = 'connecting';
    } else if (stateName === 'close') {
      const status = lastDisconnect?.error?.output?.statusCode;
      connection = status === DisconnectReason.loggedOut ? 'logged_out' : 'disconnected';
      logger.warn({ status }, 'WhatsApp disconnected');
      if (connection !== 'logged_out') {
        scheduleReconnect();
      }
    }
  });
}

const server = http.createServer(async (req, res) => {
  if (!safeEqual(req.headers.authorization?.replace(/^Bearer /, ''))) {
    json(res, 401, { error: 'Unauthorized' });
    return;
  }
  try {
    const url = new URL(req.url, `http://${host}:${port}`);
    if (req.method === 'GET' && url.pathname === '/status') {
      json(res, 200, { status: connection, ownerJid: ownerJid(ownerPhone), queued: inbox.items.length,
        newestId: inbox.nextId - 1 });
    } else if (req.method === 'GET' && url.pathname === '/messages') {
      const after = Number(url.searchParams.get('after') ?? '0');
      json(res, 200, { messages: inbox.list(after), cursor: inbox.nextId - 1 });
    } else if (req.method === 'POST' && url.pathname === '/pair') {
      const payload = await bodyJson(req);
      if (normalizePhone(payload.phone) !== ownerPhone) return json(res, 403, { error: 'Phone does not match bridge owner' });
      if (connection === 'open') return json(res, 409, { error: 'Already connected' });
      if (connection === 'logged_out') return json(res, 409, { error: 'Session logged out; stop bridge and remove auth directory to relink' });
      if (pairingRequested || !socket) return json(res, 409, { error: 'Pairing already requested or socket not ready; retry shortly' });
      pairingRequested = true;
      try {
        const code = await socket.requestPairingCode(ownerPhone);
        json(res, 200, { code });
      } catch (error) {
        pairingRequested = false;
        logger.warn({ error }, 'Pairing request failed');
        json(res, 503, { error: 'Pairing failed; wait for connecting state and retry' });
      }
    } else if (req.method === 'POST' && url.pathname === '/send') {
      const payload = await bodyJson(req);
      if (payload.to !== ownerJid(ownerPhone) || typeof payload.message !== 'string' ||
          !payload.message.trim() || payload.message.length > 4000) {
        return json(res, 400, { error: 'Only a text message to the owner chat (max 4000 chars) is allowed' });
      }
      if (connection !== 'open' || !socket) return json(res, 503, { error: 'WhatsApp is not connected' });
      const key = String(payload.requestId ?? '');
      if (key && sendResults.has(key)) return json(res, 200, sendResults.get(key));
      pendingReplyBodies.set(payload.message.trim(), Date.now() + 120_000);
      if (pendingReplyBodies.size > 1000) {
        for (const [body, expiry] of pendingReplyBodies) if (expiry < Date.now()) pendingReplyBodies.delete(body);
      }
      const sent = await socket.sendMessage(payload.to, { text: payload.message });
      if (sent?.key?.id) sentIds.add(sent.key.id);
      const result = { ok: true, id: sent?.key?.id ?? null };
      if (key) {
        sendResults.set(key, result);
        if (sendResults.size > 1000) sendResults.delete(sendResults.keys().next().value);
      }
      json(res, 200, result);
    } else {
      json(res, 404, { error: 'Unknown endpoint' });
    }
  } catch (error) {
    if (error.code === 'CURSOR_EXPIRED') return json(res, 410, { error: error.message, oldestId: error.oldest });
    logger.warn({ error }, 'Bridge request failed');
    json(res, error.status ?? 500, { error: error.status ? error.message : 'Bridge operation failed' });
  }
});

server.listen(port, host, () => logger.info({ host, port }, 'OmniDev WhatsApp bridge listening'));
connect().catch(error => {
  connection = 'disconnected';
  logger.error({ error }, 'Initial connection failed');
  scheduleReconnect();
});
process.on('SIGINT', () => { clearTimeout(retryTimer); server.close(); socket?.end?.(undefined); });
process.on('SIGTERM', () => { clearTimeout(retryTimer); server.close(); socket?.end?.(undefined); });
