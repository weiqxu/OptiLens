const stable = (o) => JSON.stringify(Object.entries(o).sort(([a], [b]) => a.localeCompare(b)));
const signature = (n) => `${n.type}|${stable(n.attributes)}|${JSON.stringify(n.fields)}`;

// Match preserved identities first, then unique structural candidates. Ambiguity is not forced.
export function diffPlans(before, after) {
  const left = new Map(before.nodes.map((n) => [n.id, n]));
  const right = new Map(after.nodes.map((n) => [n.id, n]));
  const matches = new Map(),
    used = new Set(),
    basis = new Map();
  function pair(a, b, why) {
    matches.set(a.id, b.id);
    used.add(b.id);
    basis.set(a.id, why);
  }
  for (const a of left.values())
    if (right.has(a.id) && a.type === right.get(a.id).type)
      pair(a, right.get(a.id), 'Node identity');
  function unique(key, why) {
    const l = new Map(),
      r = new Map();
    for (const n of left.values())
      if (!matches.has(n.id)) {
        const k = key(n, left);
        l.set(k, [...(l.get(k) || []), n]);
      }
    for (const n of right.values())
      if (!used.has(n.id)) {
        const k = key(n, right);
        r.set(k, [...(r.get(k) || []), n]);
      }
    for (const [key, ns] of l)
      if (ns.length === 1 && r.get(key)?.length === 1) pair(ns[0], r.get(key)[0], why);
  }
  unique(signature, 'Unique attribute match');
  const lineage = (node, nodes, seen = new Set()) => {
    if (seen.has(node.id)) return [];
    seen.add(node.id);
    if (node.attributes.table) return [node.attributes.table];
    return node.inputs.flatMap((id) => lineage(nodes.get(id), nodes, seen));
  };
  unique(
    (n, map) => n.type + '|' + [...new Set(lineage(n, map))].sort().join('|'),
    'Unique operator/table-origin match (inferred)',
  );
  const parents = (plan) => {
    const map = new Map(plan.nodes.map((n) => [n.id, []]));
    for (const n of plan.nodes) for (const id of n.inputs) map.get(id).push(n.id);
    return map;
  };
  const lp = parents(before),
    rp = parents(after),
    leftMarks = new Map(),
    rightMarks = new Map();
  for (const a of left.values()) {
    if (!matches.has(a.id)) {
      leftMarks.set(a.id, { label: 'Only before', basis: 'No reliable correspondence' });
      continue;
    }
    const b = right.get(matches.get(a.id));
    let label = '';
    if (signature(a) !== signature(b)) label = 'Attributes changed';
    else if (
      JSON.stringify(a.inputs.map((id) => matches.get(id) ?? '?')) !== JSON.stringify(b.inputs)
    )
      label = 'Input links changed';
    else if (
      JSON.stringify(
        lp
          .get(a.id)
          .map((id) => matches.get(id) ?? '?')
          .sort(),
      ) !== JSON.stringify([...rp.get(b.id)].sort())
    )
      label = 'Parent links changed';
    if (label) {
      const mark = { label, basis: basis.get(a.id) };
      leftMarks.set(a.id, mark);
      rightMarks.set(b.id, mark);
    }
  }
  for (const b of right.values())
    if (!used.has(b.id))
      rightMarks.set(b.id, { label: 'Only after', basis: 'No reliable correspondence' });
  return { left: leftMarks, right: rightMarks, matches };
}

export function layoutPlan(plan) {
  const nodes = new Map(plan.nodes.map((n) => [n.id, n]));
  const depths = new Map(),
    order = [];
  function visit(id, depth) {
    if ((depths.get(id) ?? -1) >= depth) return;
    if (!depths.has(id)) order.push(id);
    depths.set(id, depth);
    nodes.get(id).inputs.forEach((c) => visit(c, depth + 1));
  }
  visit(plan.rootId, 0);
  const ranks = new Map();
  for (const id of order) {
    const d = depths.get(id);
    if (!ranks.has(d)) ranks.set(d, []);
    ranks.get(d).push(id);
  }
  const width = Math.max(286, ...[...ranks.values()].map((ids) => ids.length * 254 + 24));
  const height = (Math.max(...ranks.keys()) + 1) * 142 + 20;
  const positions = new Map();
  for (const [depth, ids] of ranks)
    ids.forEach((id, i) =>
      positions.set(id, { x: (width - ids.length * 254) / 2 + i * 254 + 12, y: 22 + depth * 142 }),
    );
  return { width, height, positions };
}

export function drawPlan(container, plan, marks, side, onSelect, zoom = 1) {
  const ns = 'http://www.w3.org/2000/svg';
  const make = (name, attrs = {}, text) => {
    const e = document.createElementNS(ns, name);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v));
    if (text !== undefined) e.textContent = text;
    return e;
  };
  const { width, height, positions } = layoutPlan(plan);
  const svg = make('svg', {
    viewBox: `0 0 ${width} ${height}`,
    width: width * zoom,
    height: height * zoom,
    role: 'group',
    'aria-label': side === 'before' ? 'Plan before' : 'Plan after',
  });
  svg.append(
    make(
      'title',
      {},
      `${plan.scope === 'full-plan' ? 'Complete plan' : 'Local expression'}, ${plan.nodes.length} ${plan.nodes.length === 1 ? 'node' : 'nodes'}. Select a node for details.`,
    ),
  );
  for (const n of plan.nodes) {
    const p = positions.get(n.id);
    for (const id of n.inputs) {
      const q = positions.get(id);
      svg.append(
        make('path', {
          d: `M ${p.x + 115} ${p.y + 100} V ${p.y + 120} H ${q.x + 115} V ${q.y}`,
          class: 'graph-edge',
        }),
      );
    }
  }
  for (const n of plan.nodes) {
    const p = positions.get(n.id),
      mark = marks.get(n.id);
    const g = make('g', {
      transform: `translate(${p.x},${p.y})`,
      class: 'plan-node' + (mark ? (side === 'before' ? ' old' : ' new') : ''),
      'data-node': n.id,
      role: 'button',
      tabindex: '0',
      'aria-label': `${n.type}, node ${n.id}${mark ? ', ' + mark.label : ''}`,
    });
    g.append(make('rect', { width: 230, height: 100, rx: 7 }));
    g.append(
      make(
        'text',
        { x: 12, y: 19, class: 'node-badge' },
        `#${n.id}  ${mark?.label || 'No marked difference'}`,
      ),
    );
    g.append(
      make(
        'text',
        { x: 12, y: 40, class: 'node-title' },
        n.type.length > 26 ? n.type.slice(0, 25) + '…' : n.type,
      ),
    );
    const expr =
      Object.entries(n.attributes)
        .filter(([k]) => k !== 'subset')
        .map(([k, v]) => `${k}=${v}`)
        .join(' · ') || n.fields.join(', ');
    const chars = [...expr];
    for (let line = 0; line < 2; line++)
      g.append(
        make(
          'text',
          { x: 12, y: 61 + line * 17, class: 'node-expression' },
          chars.slice(line * 29, (line + 1) * 29).join('') +
            (line === 1 && chars.length > 58 ? '…' : ''),
        ),
      );
    g.append(make('title', {}, `${n.type}\n${expr}\nOutput: ${n.fields.join(', ')}`));
    const select = () => onSelect(n, mark, side);
    g.addEventListener('click', select);
    g.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        select();
      }
    });
    svg.append(g);
  }
  container.replaceChildren(svg);
}
