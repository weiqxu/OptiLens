import test from 'node:test';
import assert from 'node:assert/strict';
import {
  History,
  connect,
  cloneSubtree,
  structuralErrors,
  autoLayout,
  remapPlan,
} from '../src/main/resources/web/plan-editor.mjs';
const plan = () => ({
  planVersion: 1,
  rootId: 'p',
  nodes: [
    { id: 's', op: 'TableScan', inputs: [], attributes: {} },
    { id: 'p', op: 'Project', inputs: ['s'], attributes: {} },
  ],
  layout: {},
});
test('connections reject cycles and shared parents; invalid drafts remain diagnosable', () => {
  const p = plan();
  assert.deepEqual(structuralErrors(p, { TableScan: 0, Project: 1 }), []);
  assert.throws(() => connect(p, 'p', 0, 'p'), /cycle/);
  p.nodes.push({ id: 'q', op: 'Project', inputs: [null], attributes: {} });
  assert.throws(() => connect(p, 'q', 0, 's'), /already has a parent/);
  assert.ok(structuralErrors(p, { TableScan: 0, Project: 1 }).length);
});
test('copying and remapping a subtree never share editor identities', () => {
  const p = plan();
  const clone = cloneSubtree(p, 'p');
  assert.notEqual(clone, 'p');
  assert.equal(new Set(p.nodes.map((n) => n.id)).size, 4);
  const child = p.nodes.find((n) => n.id === clone).inputs[0];
  assert.notEqual(child, 's');
  assert.deepEqual(p.nodes.find((n) => n.id === child).attributes, {});
  autoLayout(p);
  assert.equal(Object.keys(p.layout).length, 4);
  const remapped = remapPlan(p);
  assert.equal(remapped.nodes.length, 4);
  assert.ok(remapped.nodes.some((n) => n.id === remapped.rootId));
});
test('undo and redo preserve both geometry and the ordered rule program', () => {
  const original = { plan: plan(), rules: ['a', 'b'] },
    h = new History(original);
  const next = structuredClone(original);
  next.rules.reverse();
  next.plan.layout.p = { x: 100, y: 200 };
  h.set(next);
  h.undo();
  assert.deepEqual(h.value, original);
  h.redo();
  assert.deepEqual(h.value, next);
  h.undo();
  h.set({ ...original, rules: [] });
  assert.equal(h.future.length, 0);
});

test('Malformed editor expressions and cyclic JSON cannot replace a working draft', async () => {
  const { validateEditorShape } = await import('../src/main/resources/web/plan-editor.mjs');
  assert.throws(
    () =>
      validateEditorShape({
        nodes: [
          {
            id: 'n',
            op: 'Project',
            inputs: [],
            attributes: { projects: [{ name: 'x', expr: { kind: 'call', op: '+', args: null } }] },
          },
        ],
      }),
    /must be an array/,
  );
  assert.throws(
    () =>
      validateEditorShape({
        nodes: [
          {
            id: 'n',
            op: 'Filter',
            inputs: ['n'],
            attributes: { condition: { kind: 'literal', type: 'BOOLEAN', value: true } },
          },
        ],
      }),
    /Cycle/,
  );
});
