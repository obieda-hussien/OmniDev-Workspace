import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { appendFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Inbox, incomingText } from '../queue.mjs';

test('cursor survives restart and duplicate WA messages do not repeat', () => {
  const dir = mkdtempSync(join(tmpdir(), 'omni-wa-'));
  try {
    const path = join(dir, 'inbox.jsonl');
    const inbox = new Inbox(path, 2);
    assert.equal(inbox.add({ waKey: 'a', body: 'first' }).id, 1);
    assert.equal(inbox.add({ waKey: 'a', body: 'first' }), null);
    inbox.add({ waKey: 'b', body: 'second' });
    inbox.add({ waKey: 'c', body: 'third' });
    inbox.add({ waKey: 'e', body: 'extra' });
    inbox.add({ waKey: 'f', body: 'extra' });
    appendFileSync(path, '{"broken":');
    const restored = new Inbox(path, 2);
    assert.deepEqual(restored.list(3).map(item => item.id), [4, 5]);
    assert.throws(() => restored.list(-1), /Invalid cursor/);
    assert.equal(restored.add({ waKey: 'd', body: 'fourth' }).id, 6);
    assert.throws(() => restored.list(1), /Cursor expired/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('only owner chat text is eligible; sent replies are excluded', () => {
  const make = (jid, id, fromMe = false) => ({
    key: { remoteJid: jid, id, fromMe }, message: { conversation: ' !help ' }
  });
  assert.equal(incomingText(make('999@s.whatsapp.net', 'a'), '201234'), null);
  assert.equal(incomingText(make('201234@g.us', 'b'), '201234'), null);
  assert.equal(incomingText(make('201234@s.whatsapp.net', 'c', true), '201234', new Set(['c'])), null);
  assert.equal(incomingText(make('201234@s.whatsapp.net', 'd'), '+201234').body, '!help');
});
