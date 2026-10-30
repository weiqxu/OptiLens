import { databaseOptions } from './database-options.mjs';
import { mountAssistant, highlightNode } from './assistant.mjs';
import { diffPlans, drawPlan, layoutPlan } from './graph.mjs';
const $ = (id) => document.getElementById(id);
const text = (id, value) => {
  $(id).textContent = value;
};
const counted = (n, label) => `${n} ${label}${n === 1 ? '' : 's'}`;
let catalog,
  rules = [],
  examples = new Map(),
  coverage = new Map(),
  selected = null,
  job = null,
  revision = 0,
  startTimer;
let assistantSelectedResult = null;
const runs = new Map(),
  zoom = { before: 1, after: 1 };
async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...options.headers },
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}
function displayName(rule) {
  const constant = rule.id.split('.').slice(1).join('.');
  return constant.includes('_')
    ? constant
        .toLowerCase()
        .replaceAll('_', ' ')
        .replace(/^./, (c) => c.toUpperCase())
    : rule.title;
}
function verified(ruleId) {
  return coverage.get(ruleId)?.status === 'verified';
}
function verifiedCount() {
  return rules.filter((rule) => verified(rule.id)).length;
}
function renderList() {
  const query = $('explorer-search').value.trim().toLowerCase(),
    category = $('explorer-category').value,
    onlyExamples = $('examples-only').checked;
  let visible = 0;
  for (const button of $('explorer-list').children) {
    const rule = rules.find((r) => r.id === button.dataset.rule);
    button.hidden = !(
      button.dataset.search.includes(query) &&
      (!category || rule.category === category) &&
      (!onlyExamples || verified(rule.id))
    );
    button.setAttribute('aria-pressed', String(rule.id === selected?.id));
    if (!button.hidden) visible++;
  }
  text('list-count', `${visible} of ${rules.length} rules · ${verifiedCount()} verified examples`);
  $('list-empty').hidden = visible > 0;
  const active = [...$('explorer-list').children].find(
    (button) => button.dataset.rule === selected?.id && !button.hidden,
  );
  if (active) {
    const list = $('explorer-list'),
      row = active.getBoundingClientRect(),
      bounds = list.getBoundingClientRect();
    if (row.top < bounds.top) list.scrollTop -= bounds.top - row.top;
    else if (row.bottom > bounds.bottom) list.scrollTop += row.bottom - bounds.bottom;
  }
}
function clearResult() {
  job = null;
  zoom.before = zoom.after = 1;
  $('example-result').hidden = true;
  for (const id of ['example-before', 'example-after', 'example-node-content'])
    $(id).replaceChildren();
  $('example-node').open = false;
}
function choose(rule) {
  clearTimeout(startTimer);
  const token = ++revision;
  selected = rule;
  clearResult();
  renderList();
  const url = new URL(location.href);
  url.searchParams.set('rule', rule.id);
  url.searchParams.set('database', catalog.database);
  history.replaceState(null, '', url);
  $('detail-empty').hidden = true;
  $('detail-content').hidden = false;
  text('detail-category', rule.category);
  text('detail-title', displayName(rule));
  text('detail-id', rule.id);
  text(
    'detail-availability',
    rule.runnable
      ? verified(rule.id)
        ? 'Verified example'
        : coverage.get(rule.id)?.status || 'Available to experiment'
      : 'Requires setup',
  );
  text('detail-description', rule.description);
  text('detail-conditions', rule.conditions);
  $('detail-source').href = rule.source;
  $('open-lab').textContent = 'Try in Query lab →';
  $('open-lab').hidden = !rule.runnable;
  $('open-lab').href = `/?database=${catalog.database}&rule=${encodeURIComponent(rule.id)}`;
  const example = examples.get(rule.id);
  $('example-section').hidden = !example;
  $('no-example').hidden = Boolean(example);
  if (!example) {
    text(
      'no-example-title',
      rule.runnable ? 'No verified example yet' : 'Additional environment required',
    );
    text(
      'no-example-note',
      coverage.get(rule.id)?.reason ||
        (rule.runnable
          ? 'This rule is in the catalog, but a reproducible example has not been added. Try it with your own SQL in Query lab. No transformation is implied.'
          : rule.unavailableReason + '. A runnable example is not available in this environment.'),
    );
    return;
  }
  const manual = example.input.inputMode === 'plan';
  $('open-lab').href =
    `/${manual ? 'plan-lab' : ''}?database=${catalog.database}&rule=${encodeURIComponent(rule.id)}`;
  $('open-lab').textContent = manual ? 'Try in Plan lab →' : 'Try in Query lab →';
  $('example-sql').setAttribute('aria-label', manual ? 'Manual plan JSON' : 'Example SQL');
  text('example-title', example.title);
  text('example-note', `${example.note} ${coverage.get(rule.id)?.reason || ''}`);
  text('example-sql', manual ? JSON.stringify(example.input.plan, null, 2) : example.input.sql);
  text('example-schema', JSON.stringify(example.input.schema, null, 2));
  $('example-program').replaceChildren(
    ...example.input.rules.map((id) => {
      const li = document.createElement('li');
      li.textContent = (id === example.id ? 'Target: ' : 'Preparation: ') + id;
      return li;
    }),
  );
  const options = example.input.options;
  text(
    'example-settings',
    catalog.executionMode === 'native-search'
      ? `Full native SQL optimization. Selected rules filter observations. Up to ${Math.min(80, options?.matchLimit ?? 100)} candidate events are recorded across the search; no user query is executed.`
      : `Match limit per stage: ${options?.matchLimit ?? 100}. Input: ${manual ? 'hand-built logical plan; SQL conversion is bypassed' : `SQL; subquery expansion ${options?.expandSubqueries === false ? 'disabled, preserving subqueries for the target rule' : 'enabled'}`}. Planner time zone: UTC. A bounded demonstration does not imply a fixed point or a globally optimal plan.`,
  );
  $('rerun-example').disabled = true;
  if (!catalog.runtime.available) {
    setStatus(catalog.runtime.message);
    return;
  }
  setStatus('Preparing the example…');
  startTimer = setTimeout(() => showExample(example, token), 200);
}
function setStatus(message, error = false) {
  text('example-status', message);
  $('example-status').className = 'status' + (error ? ' error' : '');
}
async function execute(example) {
  const submitted = await api('/api/runs', { method: 'POST', body: JSON.stringify(example.input) });
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    const result = await api(`/api/runs/${submitted.id}`);
    if (result.status === 'succeeded') return result;
    if (result.status === 'failed') throw new Error(result.error || 'The example failed.');
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('The example is still pending. Check Recent runs in Query lab.');
}
async function showExample(example, token) {
  if (token !== revision) return;
  setStatus(`Running this rule in ${catalog.database}…`);
  let pending = runs.get(example.id);
  if (!pending) {
    pending = execute(example);
    runs.set(example.id, pending);
  }
  try {
    const result = await pending;
    if (token !== revision) return;
    job = result;
    const stage = targetStage();
    $('example-view').replaceChildren(
      new Option(
        catalog.executionMode === 'native-search'
          ? 'Complete plan · native search boundaries'
          : 'Complete plan · stage boundary',
        'stage',
      ),
      ...stage.events.map(
        (event, index) => new Option(`Local event ${index + 1} · ${event.rule}`, String(index)),
      ),
    );
    $('download-example').href = `/api/runs/${job.id}/download`;
    $('example-result').hidden = false;
    setStatus(
      stage.events.length
        ? `Completed · ${counted(stage.events.length, catalog.executionMode === 'native-search' ? 'candidate' : 'transformation')} · ${counted(stage.attempts.length, 'attempt')} · ${job.result.engine} ${job.result.engineVersion}`
        : 'Completed without a recorded transformation. Inspect the plans and attempts; no rewrite is claimed.',
    );
    $('example-limitations').replaceChildren(
      ...job.result.limitations.map((value) => {
        const li = document.createElement('li');
        li.textContent = value;
        return li;
      }),
    );
    renderPlans(true);
  } catch (error) {
    if (runs.get(example.id) === pending) runs.delete(example.id);
    if (token === revision) setStatus(error.message, true);
  } finally {
    if (token === revision) $('rerun-example').disabled = false;
  }
}
function targetStage() {
  return job.result.stages.find((stage) => stage.id === selected.id);
}
function renderPlans(fit = false) {
  if (!job) return;
  const stage = targetStage(),
    value = $('example-view').value;
  const pair = value === 'stage' ? stage : stage.events[Number(value)];
  text(
    'example-scope',
    value === 'stage'
      ? `Complete plans immediately before and after ${selected.id}. ${job.result.stages.length - 1} preparation stages precede the target; their transformations are excluded from this comparison. Local events are available in the View menu.`
      : 'Local expressions from one rule invocation. This event does not represent the entire query plan or prove final candidate selection.',
  );
  const diff = diffPlans(pair.before, pair.after);
  if (fit)
    for (const side of ['before', 'after'])
      zoom[side] = Math.min(
        1,
        Math.max(0.65, ($('example-' + side).clientWidth - 16) / layoutPlan(pair[side]).width),
      );
  assistantSelectedResult = null;
  const inspect = (node, mark, side) => {
    assistantSelectedResult = { kind: 'result', side, nodeId: node.id };
    text(
      'example-node-content',
      JSON.stringify({ side, difference: mark || 'No marked difference', ...node }, null, 2),
    );
    $('example-node').open = true;
  };
  drawPlan($('example-before'), pair.before, diff.left, 'before', inspect, zoom.before);
  drawPlan($('example-after'), pair.after, diff.right, 'after', inspect, zoom.after);
  text('difference-summary', `${diff.left.size} before / ${diff.right.size} after nodes marked`);
  text('example-raw-before', pair.before.raw);
  text('example-raw-after', pair.after.raw);
  text('example-attempts', JSON.stringify(stage.attempts, null, 2));
  $('example-node').open = false;
  text('example-node-content', 'Select an operator to inspect its expressions and attributes.');
}
$('explorer-search').addEventListener('input', renderList);
$('explorer-category').addEventListener('change', renderList);
$('examples-only').addEventListener('change', renderList);
$('explorer-database').addEventListener('change', () =>
  loadEngine($('explorer-database').value).catch((error) => text('catalog-status', error.message)),
);
$('example-view').addEventListener('change', () => renderPlans(true));
$('rerun-example').addEventListener('click', () => {
  const example = examples.get(selected.id);
  runs.delete(example.id);
  clearResult();
  $('rerun-example').disabled = true;
  showExample(example, ++revision);
});
document.querySelectorAll('[data-zoom]').forEach((button) =>
  button.addEventListener('click', () => {
    const [side, delta] = button.dataset.zoom.split(':');
    zoom[side] =
      Number(delta) === 0 ? 1 : Math.min(1.6, Math.max(0.65, zoom[side] + Number(delta) * 0.15));
    renderPlans();
  }),
);
async function loadEngine(database, requested) {
  document.querySelectorAll('.page-nav a').forEach((link) => {
    const url = new URL(link.href);
    url.searchParams.set('database', database);
    link.href = url.pathname + url.search;
  });

  const token = ++revision;
  clearTimeout(startTimer);
  clearResult();
  text('catalog-status', 'Loading the engine catalog…');
  const [loaded, library] = await Promise.all([
    api('/api/catalog?database=' + database),
    api('/api/rule-examples?database=' + database),
  ]);
  if (token !== revision) return;
  catalog = loaded;
  $('explorer-list').replaceChildren();
  $('explorer-search').value = '';
  $('examples-only').checked = false;
  rules = catalog.rules.filter((rule) => rule.category !== 'Common rule groups');
  examples = new Map(library.examples.map((example) => [example.id, example]));
  coverage = new Map(library.coverage.map((item) => [item.id, item]));
  $('explorer-database').replaceChildren(...databaseOptions(catalog.databases));
  $('explorer-database').value = database;
  $('explorer-category').replaceChildren(
    new Option('All categories', ''),
    ...Array.from(
      new Set(rules.map((rule) => rule.category)),
      (category) => new Option(category, category),
    ),
  );
  text('catalog-total', rules.length);
  text('catalog-scope', catalog.scope);
  for (const rule of rules) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'rule-item';
    button.dataset.rule = rule.id;
    button.dataset.search = [rule.id, rule.description, rule.category, ...rule.implementations]
      .join(' ')
      .toLowerCase();
    const title = document.createElement('strong');
    title.textContent = displayName(rule);
    const id = document.createElement('small');
    id.textContent = rule.id;
    const tag = document.createElement('small');
    tag.textContent = verified(rule.id)
      ? '● Verified example'
      : examples.has(rule.id)
        ? `Example · ${coverage.get(rule.id)?.status || 'unverified'}`
        : rule.runnable
          ? 'No example yet'
          : 'Requires setup';
    if (verified(rule.id)) tag.className = 'example-tag';
    button.append(title, id, tag);
    button.addEventListener('click', () => choose(rule));
    $('explorer-list').append(button);
  }
  for (const id of ['explorer-database', 'explorer-search', 'explorer-category', 'examples-only'])
    $(id).disabled = false;
  const initial =
    rules.find((rule) => rule.id === requested) ||
    rules.find((rule) => rule.id === 'CoreRules.PROJECT_REDUCE_EXPRESSIONS') ||
    rules.find((rule) => examples.has(rule.id)) ||
    rules[0];
  text(
    'catalog-status',
    `${rules.length} catalog rules · ${verifiedCount()} verified examples / ${rules.filter((rule) => rule.runnable).length} runnable rules · Select a rule to explore. Examples run locally and appear in Query lab history.`,
  );
  if (requested && !rules.some((rule) => rule.id === requested))
    text(
      'catalog-status',
      'The requested rule was not found. Showing the constant projection example.',
    );
  if (!catalog.runtime.available) text('catalog-status', catalog.runtime.message);
  $('verification-link').hidden = database !== 'starrocks';
  choose(initial);
}
try {
  const params = new URL(location.href).searchParams;
  await loadEngine(params.get('database') || 'calcite', params.get('rule'));
} catch (error) {
  text('catalog-status', 'Unable to load the rule explorer: ' + error.message);
  $('catalog-status').className = 'status error';
}

if (catalog)
  await mountAssistant({
    page: 'rules',
    context() {
      return {
        page: 'rules',
        database: catalog.database,
        engineVersion: catalog.engineVersion,
        input: examples.get(selected?.id)?.input || null,
        ruleId: selected?.id,
        stamp: { revision, ruleId: selected?.id },
        selectedNode: assistantSelectedResult,
        validationMessage: $('example-status').textContent,
        runId: job?.id || null,
        stage: job ? job.result.stages.findIndex((s) => s.id === selected.id) : -1,
        event: job && $('example-view').value !== 'stage' ? Number($('example-view').value) : -1,
      };
    },
    async apply() {
      throw Error('Open the suggestion in a Lab to apply it.');
    },
    async focus(ref) {
      const record = await api('/api/runs/' + encodeURIComponent(ref.runId));
      const ruleId = record.result.stages[ref.stage]?.id;
      if (!ruleId) throw Error('Rule stage is no longer available.');
      if (catalog.database !== ref.database) await loadEngine(ref.database, ruleId);
      const rule = rules.find((r) => r.id === ruleId);
      if (!rule) throw Error('This rule is not in the current catalog.');
      choose(rule);
      clearTimeout(startTimer);
      ++revision;
      job = record;
      const stage = targetStage();
      $('example-result').hidden = false;
      $('example-section').hidden = false;
      $('example-view').replaceChildren(
        new Option(
          catalog.executionMode === 'native-search'
            ? 'Complete plan · native search boundaries'
            : 'Complete plan · stage boundary',
          'stage',
        ),
        ...stage.events.map((e, i) => new Option('Local event ' + (i + 1), String(i))),
      );
      $('example-view').value = ref.event < 0 ? 'stage' : String(ref.event);
      renderPlans();
      highlightNode($('example-' + ref.side), ref.nodeId);
    },
  });
