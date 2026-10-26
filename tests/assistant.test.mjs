import { webcrypto } from 'node:crypto';
globalThis.crypto ??= webcrypto;
import test from 'node:test';
import assert from 'node:assert/strict';
import { readEvents, fingerprint } from '../src/main/resources/web/assistant.mjs';
function response(text, size = 1) {
  const bytes = new TextEncoder().encode(text);
  let offset = 0;
  return new Response(
    new ReadableStream({
      pull(c) {
        if (offset >= bytes.length) {
          c.close();
          return;
        }
        c.enqueue(bytes.slice(offset, offset + size));
        offset += size;
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  );
}
test('stream parser reconstructs UTF-8 across every transport byte', async () => {
  const events = [];
  await readEvents(
    response('event: text\ndata: {"text":"优化规则"}\n\n: heartbeat\n\nevent: done\ndata: {}\n\n'),
    (type, data) => events.push([type, data]),
  );
  assert.deepEqual(events, [
    ['text', { text: '优化规则' }],
    ['done', {}],
  ]);
});
test('incomplete streams and explicit error events never report success', async () => {
  await assert.rejects(
    readEvents(response('event: text\ndata: {"text":"partial"}\n\n'), () => {}),
    /interrupted/,
  );
  await assert.rejects(
    readEvents(response('event: error\ndata: {"error":"gateway failed"}\n\n'), () => {}),
    /gateway failed/,
  );
});
test('input versions include schema, rule order and pending editor state', async () => {
  const base = { input: { sql: 'SELECT 1', rules: ['a', 'b'] }, stamp: 1 };
  assert.equal(await fingerprint(base), await fingerprint(structuredClone(base)));
  assert.notEqual(await fingerprint(base), await fingerprint({ ...base, stamp: 2 }));
  assert.notEqual(
    await fingerprint(base),
    await fingerprint({ input: { ...base.input, rules: ['b', 'a'] }, stamp: 1 }),
  );
});
