import { appendFileSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

/** Append-only local inbox. IDs are monotonic across restarts; duplicate WA keys are ignored. */
export class Inbox {
  constructor(path, maxEntries = 5000) {
    this.path = path;
    this.maxEntries = maxEntries;
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    this.items = [];
    let damagedTail = false;
    try {
      for (const line of readFileSync(path, 'utf8').split('\n')) {
        if (!line) continue;
        try {
          const item = JSON.parse(line);
          if (Number.isSafeInteger(item.id) && item.id > 0) this.items.push(item);
        } catch { damagedTail = true; }
      }
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    this.items.sort((a, b) => a.id - b.id);
    this.nextId = (this.items.at(-1)?.id ?? 0) + 1;
    this.keys = new Set(this.items.map(item => item.waKey));
    if (damagedTail) this.compact();
  }

  add(message) {
    if (!message.waKey || this.keys.has(message.waKey)) return null;
    const item = { ...message, id: this.nextId, timestamp: Date.now() };
    appendFileSync(this.path, `${JSON.stringify(item)}\n`, { mode: 0o600 });
    this.items.push(item);
    this.keys.add(item.waKey);
    this.nextId++;
    if (this.items.length > this.maxEntries + Math.min(500, this.maxEntries)) this.compact();
    return item;
  }

  list(after, limit = 50) {
    if (!Number.isSafeInteger(after) || after < 0) throw new Error('Invalid cursor');
    const oldest = this.items[0]?.id ?? this.nextId;
    if (after !== 0 && after < oldest - 1) {
      const error = new Error('Cursor expired; restart at the oldest available message');
      error.code = 'CURSOR_EXPIRED';
      error.oldest = oldest;
      throw error;
    }
    return this.items.filter(item => item.id > after).slice(0, Math.min(50, limit));
  }

  compact() {
    this.items = this.items.slice(-this.maxEntries);
    this.keys = new Set(this.items.map(item => item.waKey));
    const temporary = `${this.path}.tmp`;
    writeFileSync(temporary, this.items.map(item => JSON.stringify(item)).join('\n') + '\n', { mode: 0o600 });
    renameSync(temporary, this.path);
  }
}

export const normalizePhone = value => String(value ?? '').replace(/[^0-9]/g, '');
export const ownerJid = phone => `${normalizePhone(phone)}@s.whatsapp.net`;

export function incomingText(message, phone, sentIds = new Set()) {
  const key = message?.key;
  const jid = key?.remoteJid;
  if (jid !== ownerJid(phone) || !key.id || sentIds.has(key.id)) return null;
  const content = message.message?.ephemeralMessage?.message ?? message.message;
  const body = content?.conversation ?? content?.extendedTextMessage?.text;
  if (typeof body !== 'string' || !body.trim()) return null;
  return { waKey: `${jid}:${key.id}`, from: jid, body: body.trim(), senderName: 'Owner', fromMe: !!key.fromMe };
}
