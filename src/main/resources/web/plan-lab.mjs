import { databaseOptions } from './database-options.mjs';
import { mountAssistant, highlightNode } from './assistant.mjs';
import { drawPlan, diffPlans } from './graph.mjs';
import {
  copy,
  scalar,
  ref,
  defaults,
  freshId,
  remapPlan,
  connect,
  cloneSubtree,
  structuralErrors,
  autoLayout,
  History,
  subtree,
  validateEditorShape,
} from './plan-editor.mjs';
const $ = (id) => document.getElementById(id),
  el = (tag, text) => {
    const e = document.createElement(tag);
    if (text !== undefined) e.textContent = text;
    return e;
  };
let capabilities,
  catalog,
  library,
  history,
  selected = null,
  zoom = 1,
  editorMode = 'canvas',
  revision = 0,
  job = null,
  busy = false,
  validatedFields = {},
  pendingPort = null;
let assistantSelectedResult = null;
const state = () => history.value;
function status(message, error = false) {
  $('lab-status').textContent = message;
  $('lab-status').className = 'status' + (error ? ' error' : '');
}
async function api(url, options = {}) {
  const r = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  });
  const data = await r.json();
  if (!r.ok) {
    const error = Error(
      data.error ||
        data.errors?.map((e) => `${e.nodeId || 'Plan'} · ${e.path}: ${e.message}`).join('\n') ||
        `Request failed (${r.status})`,
    );
    error.issues = data.errors;
    throw error;
  }
  return data;
}
function act(fn) {
  try {
    fn();
  } catch (e) {
    status(e.message, true);
  }
}
function button(text, fn, label) {
  const b = el('button', text);
  b.type = 'button';
  if (label) b.setAttribute('aria-label', label);
  b.onclick = () => act(fn);
  return b;
}
function field(parent, label, value, change, type = 'text', choices = null) {
  const l = el('label');
  l.className = 'property-field';
  l.append(el('span', label));
  let input;
  if (choices) {
    input = el('select');
    for (const c of choices) {
      const [v, title] = Array.isArray(c) ? c : [c, c];
      input.add(new Option(title, v));
    }
    input.value = String(value ?? '');
  } else {
    input = el('input');
    input.type = type;
    if (type === 'checkbox') input.checked = !!value;
    else input.value = value ?? '';
  }
  input.setAttribute('aria-label', label);
  input.onchange = () =>
    act(() =>
      change(
        type === 'checkbox'
          ? input.checked
          : type === 'number'
            ? input.value === ''
              ? null
              : Number(input.value)
            : input.value,
      ),
    );
  l.append(input);
  parent.append(l);
  return input;
}
function commit(fn) {
  const next = copy(state());
  fn(next);
  history.set(next);
  revision++;
  validatedFields = {};
  $('lab-preview-panel').hidden = true;
  render();
  if (job)
    $('lab-result-note').textContent =
      'Inputs changed. The previous run remains visible; run again to collect new evidence.';
}
function replace(next) {
  history.set(next);
  revision++;
  selected = next.plan.rootId;
  validatedFields = {};
  $('lab-json').value = JSON.stringify(next, null, 2);
  $('lab-preview-panel').hidden = true;
  render();
}
function updateNode(fn) {
  commit((s) => {
    const n = s.plan.nodes.find((n) => n.id === selected);
    if (!n) throw Error('Select a node.');
    fn(n, s);
  });
}
function attrs(fn) {
  updateNode((n) => fn(n.attributes));
}
function nameFields(id, seen = new Set()) {
  if (validatedFields[id]) return validatedFields[id];
  if (seen.has(id)) return [];
  seen = new Set(seen);
  seen.add(id);
  const n = state().plan.nodes.find((n) => n.id === id);
  if (!n) return [];
  const a = n.attributes,
    child = (i) => nameFields(n.inputs[i], seen);
  if (n.op === 'TableScan')
    return (state().schema.find((t) => t.name === a.table)?.columns || []).map(
      (c) => c.name + ': ' + c.type,
    );
  if (n.op === 'Values') return (a.columns || []).map((c) => c.name + ': ' + c.type);
  if (['Project', 'Calc'].includes(n.op)) return (a.projects || []).map((p) => p.name);
  if (n.op === 'Aggregate')
    return [
      ...(a.groupKeys || []).map((i) => child(0)[i] || `Field ${i}`),
      ...(a.calls || []).map((c) => c.name),
    ];
  if (n.op === 'Join')
    return ['SEMI', 'ANTI'].includes(a.joinType) ? child(0) : [...child(0), ...child(1)];
  if (n.op === 'MultiJoin') return n.inputs.flatMap((_, i) => child(i));
  return child(0);
}
function inputChoices(n, input = 0) {
  return nameFields(n.inputs[input]).map((name, i) => [String(i), `${i} · ${name}`]);
}
function expression(parent, value, change, n, label = 'Expression', depth = 0) {
  if (depth > 32) {
    parent.append(el('p', 'Expression is too deep.'));
    return;
  }
  const box = el('div');
  box.className = 'expr';
  box.dataset.expression = label;
  parent.append(box);
  field(
    box,
    label + ' kind',
    value?.kind || 'literal',
    (kind) =>
      change(
        kind === 'ref'
          ? ref()
          : kind === 'call'
            ? { kind, op: '=', args: [ref(), scalar()] }
            : kind === 'cast'
              ? { kind, type: 'INTEGER', nullable: true, expr: ref() }
              : scalar(),
      ),
    'text',
    ['ref', 'literal', 'call', 'cast'],
  );
  const set = (key, v) => change({ ...value, [key]: v });
  if (value?.kind === 'ref') {
    const choices = n.inputs.flatMap((id, input) =>
      nameFields(id).map((f, index) => [
        `${input}:${index}`,
        `Input ${input}${n.op === 'Join' ? (input === 0 ? ' (left)' : ' (right)') : ''} · ${index}: ${f}`,
      ]),
    );
    field(
      box,
      label + ' field',
      `${value.input}:${value.index}`,
      (v) => {
        const [input, index] = v.split(':').map(Number);
        change({ kind: 'ref', input, index });
      },
      'text',
      choices.length ? choices : [['0:0', 'Connect an input first']],
    );
  } else if (value?.kind === 'literal' || value?.kind === 'cast') {
    field(
      box,
      label + ' type',
      value.type || 'INTEGER',
      (v) => {
        const next = { ...value, type: v };
        delete next.precision;
        delete next.scale;
        if (value.kind === 'literal')
          next.value =
            v === 'BOOLEAN'
              ? true
              : v === 'VARCHAR'
                ? 'text'
                : v === 'DATE'
                  ? '2026-01-01'
                  : v === 'TIMESTAMP'
                    ? '2026-01-01 00:00:00'
                    : 1;
        change(next);
      },
      'text',
      capabilities.types,
    );
    field(
      box,
      label + ' nullable',
      value.nullable !== false,
      (v) => set('nullable', v),
      'checkbox',
    );
    if (value.type === 'DECIMAL') {
      field(box, label + ' precision', value.precision ?? 18, (v) => set('precision', v), 'number');
      field(box, label + ' scale', value.scale ?? 2, (v) => set('scale', v), 'number');
    }
    if (value.type === 'VARCHAR')
      field(box, label + ' length', value.precision ?? 255, (v) => set('precision', v), 'number');
    if (value.kind === 'cast')
      expression(box, value.expr, (v) => set('expr', v), n, label + ' operand', depth + 1);
    else {
      field(
        box,
        label + ' NULL',
        value.value === null,
        (v) => set('value', v ? null : value.type === 'BOOLEAN' ? false : 1),
        'checkbox',
      );
      if (value.value !== null)
        field(
          box,
          label + ' value',
          value.value,
          (v) => set('value', value.type === 'BOOLEAN' ? v === 'true' : v),
          'text',
          value.type === 'BOOLEAN' ? ['true', 'false'] : null,
        );
    }
  } else if (value?.kind === 'call') {
    field(
      box,
      label + ' operator',
      value.op,
      (op) => {
        const unary = ['NOT', 'IS NULL', 'IS NOT NULL', 'IS TRUE', 'IS FALSE'].includes(op),
          size = unary ? 1 : op === 'CASE' ? 3 : 2;
        const args = Array.from({ length: size }, (_, i) => value.args?.[i] || scalar());
        if (op === 'CASE') args[0] = scalar(true, 'BOOLEAN');
        change({ ...value, op, args });
      },
      'text',
      capabilities.expressions,
    );
    (value.args || []).forEach((arg, i) =>
      expression(
        box,
        arg,
        (v) => {
          const next = copy(value);
          next.args[i] = v;
          change(next);
        },
        n,
        `${label} argument ${i + 1}`,
        depth + 1,
      ),
    );
    if (['AND', 'OR', 'COALESCE', 'CASE'].includes(value.op)) {
      box.append(
        button('Add argument', () => {
          const next = copy(value);
          if (value.op === 'CASE') next.args.splice(-1, 0, scalar(true, 'BOOLEAN'), scalar());
          else next.args.push(value.op === 'COALESCE' ? scalar() : scalar(true, 'BOOLEAN'));
          change(next);
        }),
      );
      const min = value.op === 'CASE' ? 3 : 2;
      if (value.args.length > min)
        box.append(
          button('Remove last argument', () => {
            const next = copy(value);
            if (value.op === 'CASE') next.args.splice(-3, 2);
            else next.args.pop();
            change(next);
          }),
        );
    }
  }
}
function list(parent, title, items, renderItem, add, remove) {
  const group = el('fieldset');
  group.className = 'property-array';
  group.append(el('legend', title));
  items.forEach((item, i) => {
    const row = el('div');
    renderItem(row, item, i);
    row.append(button('Remove', () => remove(i), `Remove ${title} item ${i + 1}`));
    group.append(row);
  });
  group.append(button('Add ' + title, add));
  parent.append(group);
}
function renderInspector() {
  const n = state().plan.nodes.find((n) => n.id === selected),
    panel = $('lab-node');
  panel.replaceChildren();
  $('lab-node-title').textContent = n ? `${n.op} · ${n.id}` : 'Node properties';
  for (const id of ['lab-root', 'lab-copy', 'lab-delete']) $(id).disabled = !n;
  if (!n) {
    panel.append(el('p', 'Select a node on the canvas.'));
    return;
  }
  const a = n.attributes;
  n.inputs.forEach((id, i) => {
    const row = el('div');
    field(
      row,
      `Input ${i}${n.op === 'Join' ? (i === 0 ? ' · Left' : ' · Right') : ''}`,
      id || '',
      (v) => commit((s) => connect(s.plan, n.id, i, v)),
      'text',
      [
        ['', 'Not connected'],
        ...state()
          .plan.nodes.filter((v) => v.id !== n.id)
          .map((v) => [v.id, v.id + ' · ' + v.op]),
      ],
    );
    if (i > 0)
      row.append(
        button('Move input up', () =>
          updateNode((n) => {
            [n.inputs[i - 1], n.inputs[i]] = [n.inputs[i], n.inputs[i - 1]];
            if (n.attributes.projectedFields)
              [n.attributes.projectedFields[i - 1], n.attributes.projectedFields[i]] = [
                n.attributes.projectedFields[i],
                n.attributes.projectedFields[i - 1],
              ];
            delete n.attributes.refCounts;
          }),
        ),
      );
    if (capabilities.operators[n.op] < 0)
      row.append(
        button('Remove input', () =>
          updateNode((n) => {
            n.inputs.splice(i, 1);
            n.attributes.projectedFields?.splice(i, 1);
            delete n.attributes.refCounts;
          }),
        ),
      );
    panel.append(row);
  });
  if (capabilities.operators[n.op] < 0)
    panel.append(
      button('Add input port', () =>
        updateNode((n) => {
          n.inputs.push(null);
          n.attributes.projectedFields?.push(null);
          delete n.attributes.refCounts;
        }),
      ),
    );
  if (n.op === 'TableScan')
    field(
      panel,
      'Table',
      a.table,
      (v) => attrs((a) => (a.table = v)),
      'text',
      state().schema.map((t) => t.name),
    );
  if (n.op === 'Values') {
    list(
      panel,
      'columns',
      a.columns,
      (row, c, i) => {
        field(row, 'Column ' + i + ' name', c.name, (v) => attrs((a) => (a.columns[i].name = v)));
        field(
          row,
          'Column ' + i + ' type',
          c.type,
          (v) =>
            attrs((a) => {
              a.columns[i].type = v;
              delete a.columns[i].precision;
              delete a.columns[i].scale;
            }),
          'text',
          capabilities.types,
        );
        field(
          row,
          'Column ' + i + ' nullable',
          c.nullable,
          (v) => attrs((a) => (a.columns[i].nullable = v)),
          'checkbox',
        );
        if (c.type === 'VARCHAR')
          field(
            row,
            'Column ' + i + ' length',
            c.precision ?? 255,
            (v) => attrs((a) => (a.columns[i].precision = v)),
            'number',
          );
        if (c.type === 'DECIMAL') {
          field(
            row,
            'Column ' + i + ' precision',
            c.precision ?? 18,
            (v) => attrs((a) => (a.columns[i].precision = v)),
            'number',
          );
          field(
            row,
            'Column ' + i + ' scale',
            c.scale ?? 2,
            (v) => attrs((a) => (a.columns[i].scale = v)),
            'number',
          );
        }
      },
      () =>
        attrs((a) => {
          a.columns.push({ name: 'field' + a.columns.length, type: 'INTEGER', nullable: true });
          a.rows.forEach((r) => r.push(null));
        }),
      (i) =>
        attrs((a) => {
          a.columns.splice(i, 1);
          a.rows.forEach((r) => r.splice(i, 1));
        }),
    );
    list(
      panel,
      'rows',
      a.rows,
      (row, values, i) =>
        a.columns.forEach((c, j) => {
          field(
            row,
            `Row ${i} · ${c.name} NULL`,
            values[j] === null,
            (v) => attrs((a) => (a.rows[i][j] = v ? null : c.type === 'BOOLEAN' ? false : 1)),
            'checkbox',
          );
          if (values[j] !== null)
            field(
              row,
              `Row ${i} · ${c.name}`,
              values[j],
              (v) => attrs((a) => (a.rows[i][j] = c.type === 'BOOLEAN' ? v === 'true' : v)),
              'text',
              c.type === 'BOOLEAN' ? ['true', 'false'] : null,
            );
        }),
      () =>
        attrs((a) =>
          a.rows.push(
            a.columns.map((c) =>
              c.nullable ? null : c.type === 'BOOLEAN' ? false : c.type === 'VARCHAR' ? '' : 1,
            ),
          ),
        ),
      (i) => attrs((a) => a.rows.splice(i, 1)),
    );
  }
  if (['Filter', 'Join', 'MultiJoin'].includes(n.op))
    expression(
      panel,
      a.condition,
      (v) =>
        attrs((a) => {
          a.condition = v;
          delete a.refCounts;
        }),
      n,
      'Condition',
    );
  if (n.op === 'Join')
    field(panel, 'Join type', a.joinType, (v) => attrs((a) => (a.joinType = v)), 'text', [
      'INNER',
      'LEFT',
      'RIGHT',
      'FULL',
      'SEMI',
      'ANTI',
    ]);
  if (['Project', 'Calc'].includes(n.op)) {
    list(
      panel,
      'projections',
      a.projects,
      (row, p, i) => {
        field(row, `Output ${i} name`, p.name, (v) => attrs((a) => (a.projects[i].name = v)));
        expression(row, p.expr, (v) => attrs((a) => (a.projects[i].expr = v)), n, `Output ${i}`);
      },
      () => attrs((a) => a.projects.push({ name: 'field' + a.projects.length, expr: ref() })),
      (i) => attrs((a) => a.projects.splice(i, 1)),
    );
    if (n.op === 'Calc') {
      field(
        panel,
        'Filter enabled',
        !!a.condition,
        (v) => attrs((a) => (a.condition = v ? scalar(true, 'BOOLEAN') : null)),
        'checkbox',
      );
      if (a.condition)
        expression(panel, a.condition, (v) => attrs((a) => (a.condition = v)), n, 'Calc condition');
    }
  }
  if (n.op === 'Aggregate') {
    const group = el('fieldset');
    group.className = 'property-array';
    group.append(el('legend', 'Grouping fields'));
    inputChoices(n).forEach(([index, title]) =>
      field(
        group,
        title,
        a.groupKeys.includes(Number(index)),
        (v) =>
          attrs(
            (a) =>
              (a.groupKeys = v
                ? [...a.groupKeys, Number(index)].sort((x, y) => x - y)
                : a.groupKeys.filter((i) => i !== Number(index))),
          ),
        'checkbox',
      ),
    );
    panel.append(group);
    list(
      panel,
      'aggregate calls',
      a.calls,
      (row, c, i) => {
        field(
          row,
          `Aggregate ${i} function`,
          c.function,
          (v) => attrs((a) => (a.calls[i].function = v)),
          'text',
          capabilities.aggregates,
        );
        field(row, `Aggregate ${i} name`, c.name, (v) => attrs((a) => (a.calls[i].name = v)));
        field(
          row,
          `Aggregate ${i} argument`,
          c.args[0] ?? '',
          (v) => attrs((a) => (a.calls[i].args = v === '' ? [] : [Number(v)])),
          'text',
          [['', '* (COUNT only)'], ...inputChoices(n)],
        );
        field(
          row,
          `Aggregate ${i} distinct`,
          c.distinct,
          (v) => attrs((a) => (a.calls[i].distinct = v)),
          'checkbox',
        );
        if (catalog.database === 'calcite')
          field(
            row,
            `Aggregate ${i} filter`,
            c.filter ?? '',
            (v) => attrs((a) => (a.calls[i].filter = v === '' ? null : Number(v))),
            'text',
            [['', 'No filter'], ...inputChoices(n)],
          );
      },
      () =>
        attrs((a) =>
          a.calls.push({
            function: 'COUNT',
            args: [],
            distinct: false,
            name: 'agg' + a.calls.length,
          }),
        ),
      (i) => attrs((a) => a.calls.splice(i, 1)),
    );
  }
  if (['Sort', 'SortExchange'].includes(n.op))
    list(
      panel,
      'sort keys',
      a.keys,
      (row, k, i) => {
        field(
          row,
          `Sort key ${i}`,
          k.index,
          (v) => attrs((a) => (a.keys[i].index = Number(v))),
          'text',
          inputChoices(n),
        );
        field(
          row,
          `Direction ${i}`,
          k.direction,
          (v) => attrs((a) => (a.keys[i].direction = v)),
          'text',
          ['ASC', 'DESC'],
        );
        field(row, `NULL order ${i}`, k.nulls, (v) => attrs((a) => (a.keys[i].nulls = v)), 'text', [
          'FIRST',
          'LAST',
        ]);
      },
      () => attrs((a) => a.keys.push({ index: 0, direction: 'ASC', nulls: 'LAST' })),
      (i) => attrs((a) => a.keys.splice(i, 1)),
    );
  if (n.op === 'Sort')
    for (const key of ['offset', 'fetch'])
      field(panel, key, a[key], (v) => attrs((a) => (a[key] = v)), 'number');
  if (n.op === 'Union' || (catalog.database === 'calcite' && ['Intersect', 'Minus'].includes(n.op)))
    field(panel, 'ALL (retain duplicates)', a.all, (v) => attrs((a) => (a.all = v)), 'checkbox');
  if (['Exchange', 'SortExchange'].includes(n.op)) {
    field(
      panel,
      'Distribution',
      a.distribution.type,
      (v) => attrs((a) => (a.distribution = v === 'HASH' ? { type: v, keys: [0] } : { type: v })),
      'text',
      ['HASH', 'SINGLETON', 'BROADCAST', 'RANDOM'],
    );
    if (a.distribution.type === 'HASH')
      inputChoices(n).forEach(([index, title]) =>
        field(
          panel,
          'Hash key · ' + title,
          a.distribution.keys.includes(Number(index)),
          (v) =>
            attrs(
              (a) =>
                (a.distribution.keys = v
                  ? [...a.distribution.keys, Number(index)]
                  : a.distribution.keys.filter((i) => i !== Number(index))),
            ),
          'checkbox',
        ),
      );
  }
  if (n.op === 'MultiJoin') {
    panel.append(el('p', 'MultiJoin uses inner joins in this release.'));
    field(
      panel,
      'Post-join filter enabled',
      !!a.postFilter,
      (v) => attrs((a) => (a.postFilter = v ? scalar(true, 'BOOLEAN') : null)),
      'checkbox',
    );
    if (a.postFilter)
      expression(
        panel,
        a.postFilter,
        (v) => attrs((a) => (a.postFilter = v)),
        n,
        'Post-join filter',
      );
    n.inputs.forEach((_, i) => {
      const fields = a.projectedFields?.[i];
      field(
        panel,
        `Input ${i} retains all fields`,
        fields == null,
        (v) =>
          attrs((a) => {
            a.projectedFields ??= n.inputs.map(() => null);
            a.projectedFields[i] = v ? null : [];
          }),
        'checkbox',
      );
      if (fields != null)
        inputChoices(n, i).forEach(([index, title]) =>
          field(
            panel,
            `Input ${i} retained · ${title}`,
            fields.includes(Number(index)),
            (v) =>
              attrs(
                (a) =>
                  (a.projectedFields[i] = v
                    ? [...a.projectedFields[i], Number(index)]
                    : a.projectedFields[i].filter((j) => j !== Number(index))),
              ),
            'checkbox',
          ),
        );
    });
  }
  panel.append(el('h3', 'Output fields'));
  panel.append(
    el('pre', nameFields(n.id).join('\n') || 'No fields yet. Validate to inspect the exact types.'),
  );
}
function svg(tag, attrs = {}, text) {
  const e = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  if (text !== undefined) e.textContent = text;
  return e;
}
function renderCanvas() {
  const p = state().plan,
    container = $('lab-canvas');
  let width = 800,
    height = 620;
  for (const n of p.nodes) {
    const pos = p.layout?.[n.id] || { x: 40, y: 40 };
    width = Math.max(width, pos.x + 250);
    height = Math.max(height, pos.y + 150);
  }
  const canvas = svg('svg', {
    width: width * zoom,
    height: height * zoom,
    viewBox: `0 0 ${width} ${height}`,
    'aria-label': 'Logical operator tree',
  });
  container.replaceChildren(canvas);
  for (const n of p.nodes) {
    const pos = p.layout?.[n.id] || { x: 40, y: 40 };
    n.inputs.forEach((id, i) => {
      const child = p.layout?.[id];
      if (!child) return;
      const x = pos.x + ((i + 1) * 210) / (n.inputs.length + 1);
      canvas.append(
        svg('path', {
          class: 'edit-edge',
          d: `M ${x} ${pos.y + 95} V ${pos.y + 120} H ${child.x + 105} V ${child.y}`,
        }),
      );
    });
  }
  for (const n of p.nodes) {
    const pos = p.layout?.[n.id] || { x: 40, y: 40 };
    const g = svg('g', {
      transform: `translate(${pos.x},${pos.y})`,
      class: 'edit-node' + (selected === n.id ? ' selected' : ''),
      'data-node': n.id,
      tabindex: 0,
      role: 'button',
      'aria-label': n.op + ' ' + n.id,
    });
    g.append(
      svg('rect', { width: 210, height: 95, rx: 8 }),
      svg('text', { x: 12, y: 25, class: 'node-name' }, n.op),
      svg('text', { x: 12, y: 46 }, n.id + (p.rootId === n.id ? ' · ROOT' : '')),
      svg(
        'text',
        { x: 12, y: 67 },
        n.op === 'TableScan' ? n.attributes.table : `${nameFields(n.id).length} output fields`,
      ),
    );
    const output = svg('circle', {
      cx: 105,
      cy: 0,
      r: 7,
      class: 'edit-port' + (pendingPort === n.id ? ' pending' : ''),
      'data-output': n.id,
      tabindex: 0,
      role: 'button',
      'aria-label': 'Output of ' + n.id,
    });
    g.append(output);
    n.inputs.forEach((_, i) => {
      g.append(
        svg('circle', {
          cx: ((i + 1) * 210) / (n.inputs.length + 1),
          cy: 95,
          r: 7,
          class: 'edit-port in-port',
          'data-parent': n.id,
          'data-slot': i,
          tabindex: 0,
          role: 'button',
          'aria-label': `Input ${i} of ${n.id}`,
        }),
        svg(
          'text',
          {
            x: ((i + 1) * 210) / (n.inputs.length + 1),
            y: 85,
            'text-anchor': 'middle',
            class: 'port-label',
          },
          n.op === 'Join' ? (i === 0 ? 'Left' : 'Right') : String(i),
        ),
      );
    });
    g.onkeydown = (e) => {
      if (e.target !== g) return;
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        assistantSelectedResult = null;
        selected = n.id;
        renderCanvas();
        renderInspector();
      }
      if (e.key === 'Delete') {
        selected = n.id;
        deleteNode();
      }
    };
    g.onpointerdown = (e) => {
      if (e.target.closest('.edit-port')) return;
      assistantSelectedResult = null;
      selected = n.id;
      renderInspector();
      const start = { x: e.clientX, y: e.clientY },
        old = copy(pos);
      let moved = false;
      const move = (ev) => {
        const dx = (ev.clientX - start.x) / zoom,
          dy = (ev.clientY - start.y) / zoom;
        if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
        g.setAttribute(
          'transform',
          `translate(${Math.max(0, old.x + dx)},${Math.max(0, old.y + dy)})`,
        );
      };
      const up = (ev) => {
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        if (moved)
          commit((s) => {
            s.plan.layout ??= {};
            s.plan.layout[n.id] = {
              x: Math.max(0, old.x + (ev.clientX - start.x) / zoom),
              y: Math.max(0, old.y + (ev.clientY - start.y) / zoom),
            };
          });
        else renderCanvas();
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    };
    canvas.append(g);
  }
  canvas.querySelectorAll('[data-output]').forEach((port) => {
    const choose = () => {
      pendingPort = port.dataset.output;
      status('Choose an input port to connect ' + pendingPort + '.');
      renderCanvas();
    };
    port.onclick = choose;
    port.onkeydown = (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        choose();
      }
    };
    port.onpointerdown = (e) => {
      e.stopPropagation();
      pendingPort = port.dataset.output;
      const up = (ev) => {
        window.removeEventListener('pointerup', up);
        const target = document.elementFromPoint(ev.clientX, ev.clientY)?.closest('[data-parent]');
        if (target) attach(target.dataset.parent, Number(target.dataset.slot));
      };
      window.addEventListener('pointerup', up);
    };
  });
  canvas.querySelectorAll('[data-parent]').forEach((port) => {
    port.onclick = () => attach(port.dataset.parent, Number(port.dataset.slot));
    port.onkeydown = (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        attach(port.dataset.parent, Number(port.dataset.slot));
      }
    };
  });
}
function attach(parent, index) {
  if (!pendingPort) {
    status('Select an output port first.');
    return;
  }
  const child = pendingPort;
  pendingPort = null;
  act(() => commit((s) => connect(s.plan, parent, index, child)));
}
function addNode(op, position) {
  act(() => {
    let id;
    commit((s) => {
      if (s.plan.nodes.length >= 250) throw Error('Plan node limit reached.');
      id = freshId(s.plan);
      const count = capabilities.operators[op];
      s.plan.nodes.push({
        id,
        op,
        inputs: Array(Math.abs(count)).fill(null),
        attributes: copy(defaults(op, s.schema)),
      });
      s.plan.layout ??= {};
      s.plan.layout[id] = position || {
        x: 60 + s.plan.nodes.length * 20,
        y: 60 + s.plan.nodes.length * 20,
      };
      if (!s.plan.rootId) s.plan.rootId = id;
    });
    selected = id;
    renderCanvas();
    renderInspector();
  });
}
function deleteNode() {
  if (!selected) return;
  commit((s) => {
    s.plan.nodes = s.plan.nodes.filter((n) => n.id !== selected);
    for (const n of s.plan.nodes) n.inputs = n.inputs.map((id) => (id === selected ? null : id));
    delete s.plan.layout?.[selected];
    if (s.scopeNodeId === selected) s.scopeNodeId = null;
    if (s.plan.rootId === selected)
      s.plan.rootId =
        s.plan.nodes.find((n) => !s.plan.nodes.some((parent) => parent.inputs.includes(n.id)))
          ?.id || null;
  });
  selected = null;
  renderInspector();
}
function renderProgram() {
  const query = $('lab-rule-search').value.toLowerCase();
  $('lab-rule-picker').replaceChildren(
    ...catalog.rules
      .filter(
        (r) =>
          r.runnable &&
          !state().rules.includes(r.id) &&
          [r.id, r.description].join(' ').toLowerCase().includes(query),
      )
      .map((r) => new Option(r.id, r.id)),
  );
  $('lab-program').replaceChildren(
    ...state().rules.map((id, i) => {
      const li = el('li', id);
      if (i > 0)
        li.append(
          button(
            '↑',
            () =>
              commit((s) => {
                [s.rules[i - 1], s.rules[i]] = [s.rules[i], s.rules[i - 1]];
              }),
            'Move rule up',
          ),
        );
      if (i < state().rules.length - 1)
        li.append(
          button(
            '↓',
            () =>
              commit((s) => {
                [s.rules[i + 1], s.rules[i]] = [s.rules[i], s.rules[i + 1]];
              }),
            'Move rule down',
          ),
        );
      li.append(button('Remove', () => commit((s) => s.rules.splice(i, 1))));
      return li;
    }),
  );
  $('lab-scope').replaceChildren(
    new Option('Whole tree', ''),
    ...state().plan.nodes.map((n) => new Option(`${n.id} · ${n.op}`, n.id)),
  );
  $('lab-scope').value = state().scopeNodeId || '';
  $('lab-limit').value = state().options?.matchLimit ?? 10;
}
function render() {
  if (!history) return;
  if (!state().plan.nodes.some((n) => n.id === selected)) selected = state().plan.rootId;
  renderCanvas();
  renderInspector();
  renderProgram();
  $('lab-schema').value = JSON.stringify(state().schema, null, 2);
  if ($('lab-mode').value !== 'json') $('lab-json').value = JSON.stringify(state(), null, 2);
  $('lab-undo').disabled = !history.past.length;
  $('lab-redo').disabled = !history.future.length;
  const errors = structuralErrors(state().plan, capabilities.operators);
  $('lab-structure').textContent = errors.length
    ? errors.join(' ')
    : `${state().plan.nodes.length} connected nodes. Validate to check expressions and types.`;
  $('lab-database').disabled = busy;
  $('lab-run').disabled = busy || errors.length > 0 || !catalog.runtime.available;
  $('lab-validate').disabled = busy || errors.length > 0 || !catalog.runtime.available;
}
function loadRequest(request) {
  if (request.inputMode !== 'plan')
    throw Error(
      'This is a SQL input. Open it in Query lab; SQL-to-canvas conversion is not supported.',
    );
  const next = copy(request);
  if (next.sql != null && (typeof next.sql !== 'string' || next.sql.trim()))
    throw Error('Plan input must not also contain SQL.');
  if (next.database != null && next.database !== catalog.database)
    throw Error('Select the matching database before applying this JSON draft.');
  if (JSON.stringify(next).length > 100000)
    throw Error('Plan request exceeds the 100 KB input limit.');
  if (!next.plan || next.plan.planVersion !== 1 || !Array.isArray(next.plan.nodes))
    throw Error('Unsupported plan document.');
  if (next.plan.nodes.length > 250) throw Error('Plan exceeds 250 nodes.');
  for (const n of next.plan.nodes) {
    if (
      !n ||
      typeof n.id !== 'string' ||
      !(n.op in capabilities.operators) ||
      !Array.isArray(n.inputs) ||
      !n.attributes ||
      typeof n.attributes !== 'object'
    )
      throw Error('Malformed node in plan document.');
  }
  let items = 0;
  function check(value, depth = 0) {
    if (depth > 64 || ++items > 20000) throw Error('Plan attributes are too large or deep.');
    if (value && typeof value === 'object')
      for (const child of Object.values(value)) check(child, depth + 1);
  }
  check(next.plan);
  for (const n of next.plan.nodes) {
    const requiredArrays =
      {
        Values: ['columns', 'rows'],
        Project: ['projects'],
        Calc: ['projects'],
        Aggregate: ['groupKeys', 'calls'],
        Sort: ['keys'],
        SortExchange: ['keys'],
      }[n.op] || [];
    for (const key of requiredArrays)
      if (!Array.isArray(n.attributes[key])) throw Error(n.id + ': ' + key + ' must be an array.');
  }
  validateEditorShape(next.plan);
  if (
    !Array.isArray(next.schema) ||
    next.schema.length === 0 ||
    next.schema.length > 16 ||
    next.schema.some(
      (t) =>
        !t ||
        typeof t.name !== 'string' ||
        !Array.isArray(t.columns) ||
        t.columns.some((c) => !c || typeof c.name !== 'string'),
    )
  )
    throw Error('Invalid table schema.');
  if (
    !Array.isArray(next.rules) ||
    next.rules.length > 32 ||
    next.rules.some((id) => typeof id !== 'string')
  )
    throw Error('Invalid rule program.');
  next.plan.layout ??= {};
  next.rules ??= [];
  next.options ??= { matchLimit: 10 };
  next.schema ??= state().schema;
  next.database = catalog.database;
  next.sql = null;
  next.scopeNodeId ??= null;
  if (next.plan.nodes.some((n) => !next.plan.layout[n.id])) autoLayout(next.plan);
  return next;
}
function inputRequest() {
  const errors = structuralErrors(state().plan, capabilities.operators);
  if (errors.length) throw Error(errors.join(' '));
  return copy(state());
}
function saveFile(value, name) {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }),
    url = URL.createObjectURL(blob),
    a = el('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function refreshHistory() {
  const list = await api('/api/runs');
  $('lab-history').replaceChildren(
    new Option('Select a run', ''),
    ...list.map(
      (r) => new Option(`${r.submittedAt} · ${r.imported ? 'Imported' : r.status}`, r.id),
    ),
  );
}
function displayError(error) {
  status(error.message, true);
  const node = error.issues?.find((e) => e.nodeId)?.nodeId;
  if (node) {
    selected = node;
    renderCanvas();
    renderInspector();
  }
}
async function validate() {
  if (busy) return;
  const token = revision;
  try {
    const request = inputRequest();
    busy = true;
    render();
    status('Validating the plan in an isolated worker…');
    const result = await api('/api/plans/validate', {
      method: 'POST',
      body: JSON.stringify(request),
    });
    if (token !== revision) return;
    validatedFields = result.fields;
    $('lab-preview-panel').hidden = false;
    drawPlan($('lab-preview'), result.snapshot, new Map(), 'before', () => {}, 0.8);
    $('lab-fields').textContent = JSON.stringify(result.fields, null, 2);
    renderInspector();
    status('Valid plan · Initial operator structure preserved.');
  } catch (e) {
    if (token === revision) displayError(e);
  } finally {
    busy = false;
    render();
  }
}
async function run() {
  if (busy) return;
  const token = revision;
  try {
    const request = inputRequest();
    busy = true;
    render();
    status('Running the selected rule program…');
    const submitted = await api('/api/runs', { method: 'POST', body: JSON.stringify(request) });
    const deadline = Date.now() + 180000;
    let result;
    while (Date.now() < deadline) {
      result = await api('/api/runs/' + submitted.id);
      if (['succeeded', 'failed'].includes(result.status)) break;
      await new Promise((r) => setTimeout(r, 500));
    }
    if (result.status !== 'succeeded')
      throw Error(result.error || 'The run is still pending. Check Recent runs.');
    if (token !== revision) return;
    showResult(result);
    status(
      `Completed · ${result.result.stages.reduce((n, s) => n + s.events.length, 0)} transformations`,
    );
    await refreshHistory();
  } catch (e) {
    if (token === revision) displayError(e);
  } finally {
    busy = false;
    render();
  }
}
function showResult(result) {
  job = result;
  $('lab-result').hidden = false;
  $('lab-download').href = `/api/runs/${job.id}/download`;
  $('lab-stage').replaceChildren(
    new Option('Initial → Final', 'overview'),
    ...job.result.stages.map((s, i) => new Option(`${i + 1} · ${s.id}`, String(i))),
  );
  $('lab-result-note').textContent = job.imported
    ? 'Imported record · Provenance is not verified by re-execution.'
    : `Real ${job.result.engine} run · The original editor input is preserved.`;
  const editable = job.result.editableResult;
  $('lab-use-result').disabled = !editable?.plan;
  $('lab-editable-note').textContent = editable?.plan
    ? 'The complete result can be copied into a new editable experiment.'
    : `Read-only result: ${editable?.reasons?.join('; ') || 'This older record has no editable plan representation.'}`;
  $('lab-limitations').replaceChildren(...job.result.limitations.map((v) => el('li', v)));
  updateViews();
}
function updateViews() {
  const stage = job.result.stages[Number($('lab-stage').value)];
  $('lab-view').replaceChildren(new Option('Complete plans', 'full'));
  if (stage?.scopeBeforeRootId) $('lab-view').add(new Option('Selected subtree', 'scope'));
  if (stage)
    stage.events.forEach((e, i) =>
      $('lab-view').add(new Option(`Event ${i + 1} · ${e.rule}`, 'event:' + i)),
    );
  renderResult();
}
function renderResult() {
  if (!job) return;
  const run = job.result,
    stage = run.stages[Number($('lab-stage').value)],
    view = $('lab-view').value;
  let before = stage?.before || run.initial,
    after = stage?.after || run.finalPlan,
    scope = 'Complete plan comparison.';
  if (view === 'scope') {
    before = subtree(before, stage.scopeBeforeRootId);
    after = subtree(after, stage.scopeAfterRootId);
    scope = 'Only the selected optimization subtree.';
  } else if (view.startsWith('event:')) {
    const event = stage.events[Number(view.split(':')[1])];
    before = event.before;
    after = event.after;
    scope = 'Local expressions from one rule invocation.';
  }
  $('lab-result-scope').textContent = scope;
  const diff = diffPlans(before, after);
  assistantSelectedResult = null;
  const inspect = (node, mark, side) => {
    assistantSelectedResult = { kind: 'result', side, nodeId: node.id };
    $('lab-result-node').textContent = JSON.stringify({ side, difference: mark, ...node }, null, 2);
  };
  drawPlan($('lab-before'), before, diff.left, 'before', inspect, 0.8);
  drawPlan($('lab-after'), after, diff.right, 'after', inspect, 0.8);
  $('lab-raw').textContent =
    before.raw + '\n\n' + after.raw + '\n\n' + JSON.stringify(stage?.attempts || [], null, 2);
}
$('lab-root').onclick = () => act(() => commit((s) => (s.plan.rootId = selected)));
$('lab-copy').onclick = () =>
  act(() => {
    let id;
    commit((s) => (id = cloneSubtree(s.plan, selected)));
    selected = id;
    renderInspector();
    renderCanvas();
  });
$('lab-delete').onclick = () => act(deleteNode);
$('lab-undo').onclick = () => {
  history.undo();
  revision++;
  validatedFields = {};
  $('lab-preview-panel').hidden = true;
  render();
};
$('lab-redo').onclick = () => {
  history.redo();
  revision++;
  validatedFields = {};
  $('lab-preview-panel').hidden = true;
  render();
};
$('lab-layout').onclick = () => commit((s) => autoLayout(s.plan));
$('lab-zoom-in').onclick = () => {
  zoom = Math.min(2, zoom + 0.15);
  renderCanvas();
};
$('lab-zoom-out').onclick = () => {
  zoom = Math.max(0.4, zoom - 0.15);
  renderCanvas();
};
$('lab-canvas').ondragover = (e) => e.preventDefault();
$('lab-canvas').ondrop = (e) => {
  e.preventDefault();
  const op = e.dataTransfer.getData('text/optilens-operator');
  if (!(op in capabilities.operators)) return;
  const b = $('lab-canvas').getBoundingClientRect();
  addNode(op, {
    x: Math.max(0, (e.clientX - b.left + $('lab-canvas').scrollLeft) / zoom - 100),
    y: Math.max(0, (e.clientY - b.top + $('lab-canvas').scrollTop) / zoom - 40),
  });
};
$('lab-mode').onchange = () => {
  const json = $('lab-mode').value === 'json';
  if (!json && editorMode === 'json') {
    act(() => {
      const next = loadRequest(JSON.parse($('lab-json').value));
      if (JSON.stringify(next) !== JSON.stringify(state())) {
        replace(next);
        status('JSON applied. Validate before running.');
      }
    });
  }
  editorMode = json ? 'json' : 'canvas';
  $('lab-json-panel').hidden = !json;
  $('lab-canvas').hidden = json;
  if (json) $('lab-json').value = JSON.stringify(state(), null, 2);
};
$('lab-json-apply').onclick = () =>
  act(() => {
    const next = loadRequest(JSON.parse($('lab-json').value));
    replace(next);
    status('JSON applied. Validate before running.');
  });
$('lab-schema-apply').onclick = () =>
  act(() => {
    const schema = JSON.parse($('lab-schema').value);
    if (
      !Array.isArray(schema) ||
      schema.length === 0 ||
      schema.length > 16 ||
      schema.some((t) => !t.name || !Array.isArray(t.columns))
    )
      throw Error('Provide 1–16 tables with names and column arrays.');
    loadRequest({ ...copy(state()), schema });
    commit((s) => (s.schema = schema));
    status('Schema updated. Validate field references before running.');
  });
$('lab-rule-search').oninput = renderProgram;
$('lab-add-rule').onclick = () =>
  act(() =>
    commit((s) => {
      const id = $('lab-rule-picker').value;
      if (!id) return;
      if (s.rules.length >= 32) throw Error('Select at most 32 rules.');
      s.rules.push(id);
    }),
  );
$('lab-scope').onchange = () => commit((s) => (s.scopeNodeId = $('lab-scope').value || null));
$('lab-limit').onchange = () =>
  commit((s) => (s.options = { ...s.options, matchLimit: Number($('lab-limit').value) }));
$('lab-validate').onclick = validate;
$('lab-run').onclick = run;
$('lab-stage').onchange = updateViews;
$('lab-view').onchange = renderResult;
$('lab-use-result').onclick = async () => {
  try {
    if (!job?.result.editableResult?.plan) return;
    await ensureEngine(job.result.input.database);
    const request = {
      ...copy(job.result.input),
      sql: null,
      inputMode: 'plan',
      plan: remapPlan(job.result.editableResult.plan),
      rules: [],
      scopeNodeId: null,
    };
    autoLayout(request.plan);
    replace(request);
    $('lab-json').value = JSON.stringify(state(), null, 2);
    $('lab-mode').value = 'canvas';
    $('lab-mode').onchange();
    status('Result copied to the canvas. Choose the next rule program; scope is the whole tree.');
  } catch (error) {
    displayError(error);
  }
};
$('lab-export').onclick = () => saveFile(state(), 'optilens-plan.json');
$('lab-import').onclick = () => $('lab-file').click();
$('lab-file').onchange = async (e) => {
  try {
    const file = e.target.files[0];
    if (!file) return;
    if (file.size > 8_000_000) throw Error('File exceeds 8 MB.');
    const data = JSON.parse(await file.text());
    if (data.formatVersion) {
      const imported = await api('/api/import', { method: 'POST', body: JSON.stringify(data) });
      showResult(imported);
      if (data.input.inputMode === 'plan') {
        await ensureEngine(data.input.database);
        replace(loadRequest(data.input));
      }
      await refreshHistory();
      status('Imported run · Provenance is not verified.');
    } else {
      const request = data.planVersion ? { ...copy(state()), plan: data } : data;
      await ensureEngine(request.database);
      replace(loadRequest(request));
      status('Plan imported. Validate to verify its types.');
    }
  } catch (error) {
    displayError(error);
  } finally {
    e.target.value = '';
  }
};
$('lab-history').onchange = async (e) => {
  if (!e.target.value) return;
  try {
    const result = await api('/api/runs/' + e.target.value);
    if (result.status !== 'succeeded') throw Error(result.error || 'This run is still pending.');
    showResult(result);
    if (result.result.input.inputMode === 'plan') {
      await ensureEngine(result.result.input.database);
      replace(loadRequest(result.result.input));
    } else status('SQL record shown read-only. Edit its SQL in Query lab.');
  } catch (error) {
    displayError(error);
  }
};
function starter() {
  const schema = copy(
    catalog.examples[0]?.input.schema || [
      {
        name: 'customers',
        columns: [
          { name: 'id', type: 'INTEGER' },
          { name: 'name', type: 'VARCHAR' },
        ],
      },
    ],
  );
  return {
    inputMode: 'plan',
    database: catalog.database,
    sql: null,
    schema,
    rules: [
      catalog.database === 'calcite'
        ? 'CoreRules.PROJECT_REDUCE_EXPRESSIONS'
        : 'StarRocks.FoldConstantsRule',
    ],
    options: { matchLimit: 10 },
    scopeNodeId: null,
    plan: {
      planVersion: 1,
      rootId: 'n2',
      nodes: [
        { id: 'n1', op: 'TableScan', inputs: [], attributes: { table: schema[0].name } },
        {
          id: 'n2',
          op: 'Project',
          inputs: ['n1'],
          attributes: {
            projects: [
              { name: 'name', expr: ref(0, 1) },
              {
                name: 'threshold',
                expr: { kind: 'call', op: '*', args: [scalar(10), scalar(20)] },
              },
            ],
          },
        },
      ],
      layout: { n2: { x: 100, y: 40 }, n1: { x: 100, y: 200 } },
    },
  };
}
$('lab-new').onclick = () => {
  replace(starter());
  status('New plan ready.');
};
$('lab-example').onchange = () => {
  const example = library.examples.find((e) => e.id === $('lab-example').value);
  if (example) {
    replace(loadRequest(example.input));
    status(example.note);
  } else replace(starter());
};
const engineDrafts = new Map();
async function ensureEngine(database = 'calcite') {
  if (catalog?.database !== database) await loadEngine(database);
}
async function loadEngine(database) {
  document.querySelectorAll('.page-nav a').forEach((link) => {
    const url = new URL(link.href);
    url.searchParams.set('database', database);
    link.href = url.pathname + url.search;
  });

  const loaded = await Promise.all([
    api('/api/plan-capabilities?database=' + database),
    api('/api/catalog?database=' + database),
    api('/api/rule-examples?database=' + database),
  ]);
  [capabilities, catalog, library] = loaded;
  $('lab-database').replaceChildren(...databaseOptions(catalog.databases));
  $('lab-database').value = database;
  $('lab-database').disabled = false;
  $('lab-palette').replaceChildren();
  for (const op of Object.keys(capabilities.operators).sort()) {
    const b = button(op, () => addNode(op));
    b.draggable = true;
    b.dataset.operator = op;
    b.ondragstart = (e) => e.dataTransfer.setData('text/optilens-operator', op);
    $('lab-palette').append(b);
  }
  $('lab-example').replaceChildren(
    new Option('Fold a constant projection', ''),
    ...library.examples
      .filter((e) => e.input.inputMode === 'plan')
      .map((e) => new Option(e.title, e.id)),
  );
  $('lab-example').disabled = false;
}
$('lab-database').onchange = async () => {
  if (busy) return;
  const database = $('lab-database').value;
  engineDrafts.set(catalog.database, copy(state()));
  $('lab-database').disabled = true;
  try {
    await loadEngine(database);
    history = new History(engineDrafts.get(database) || starter());
    selected = state().plan.rootId;
    revision++;
    validatedFields = {};
    $('lab-preview-panel').hidden = true;
    render();
    status(catalog.runtime.message);
  } catch (error) {
    displayError(error);
  } finally {
    $('lab-database').disabled = false;
  }
};
try {
  await loadEngine(new URL(location.href).searchParams.get('database') || 'calcite');
  history = new History(starter());
  selected = state().plan.rootId;
  const requested = new URL(location.href).searchParams.get('rule');
  if (requested) {
    const example = library.examples.find(
      (e) => e.id === requested && e.input.inputMode === 'plan',
    );
    if (example) {
      history = new History(loadRequest(example.input));
      selected = state().plan.rootId;
      $('lab-example').value = example.id;
    } else if (catalog.rules.some((r) => r.id === requested && r.runnable))
      history.value.rules = [requested];
  }
  const runId = new URL(location.href).searchParams.get('run');
  if (runId) {
    const result = await api('/api/runs/' + encodeURIComponent(runId));
    if (result.status === 'succeeded') {
      showResult(result);
      if (result.result.input.inputMode === 'plan') {
        await ensureEngine(result.result.input.database);
        history = new History(loadRequest(result.result.input));
        selected = state().plan.rootId;
      }
    }
  }
  render();
  await refreshHistory();
  status(
    catalog.runtime.available
      ? 'Ready · Build a plan, validate it, and choose a rule program.'
      : catalog.runtime.message,
  );
} catch (e) {
  status('Unable to load Plan Lab: ' + e.message, true);
  for (const id of ['lab-run', 'lab-validate']) $(id).disabled = true;
}

if (catalog && history)
  await mountAssistant({
    page: 'plan-lab',
    context() {
      const sameEngine = job?.result.input.database === catalog.database;
      const stage =
        sameEngine && $('lab-stage').value !== 'overview' ? Number($('lab-stage').value) : -1;
      const view = $('lab-view').value;
      return {
        page: 'plan-lab',
        database: catalog.database,
        engineVersion: catalog.engineVersion,
        input: copy(state()),
        stamp: {
          revision,
          json: $('lab-mode').value === 'json' ? $('lab-json').value : null,
          schema: $('lab-schema').value,
        },
        selectedNode: assistantSelectedResult || { kind: 'draft', nodeId: selected },
        validationMessage: $('lab-status').textContent,
        runId: sameEngine ? job.id : null,
        stage,
        event: stage >= 0 && view.startsWith('event:') ? Number(view.split(':')[1]) : -1,
      };
    },
    async apply(input) {
      if (busy) throw Error('Wait for the current operation to finish.');
      await ensureEngine(input.database);
      replace(loadRequest(input));
      $('lab-mode').value = 'canvas';
      editorMode = 'canvas';
      $('lab-json-panel').hidden = true;
      $('lab-canvas').hidden = false;
      status('Assistant suggestion applied. Run to collect optimizer evidence.');
    },
    async focus(ref) {
      if (ref.kind === 'draft') {
        selected = ref.nodeId;
        $('lab-mode').value = 'canvas';
        editorMode = 'canvas';
        $('lab-json-panel').hidden = true;
        $('lab-canvas').hidden = false;
        renderCanvas();
        renderInspector();
        highlightNode($('lab-canvas'), ref.nodeId);
        return;
      }
      if (job?.id !== ref.runId) {
        const record = await api('/api/runs/' + encodeURIComponent(ref.runId));
        showResult(record);
      }
      $('lab-stage').value = ref.stage < 0 ? 'overview' : String(ref.stage);
      updateViews();
      $('lab-view').value = ref.event < 0 ? 'full' : 'event:' + ref.event;
      renderResult();
      highlightNode($('lab-' + ref.side), ref.nodeId);
    },
  });
