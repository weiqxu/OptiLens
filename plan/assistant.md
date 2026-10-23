# OptiLens Assistant

## Behavior and language

Assistant is a shared, collapsible panel in Plan Lab, Query Lab and Rule Explorer. It explains the current input and real optimizer events, attaches source and node references, and proposes complete candidate inputs for review. Model replies follow the language of the question; UI controls, application messages, documentation, identifiers and comments remain English.

The user sends each question explicitly. Sending captures the page, database/version, current input, selection, latest reported validation message and selected run/stage/event. Edits do not silently send additional requests. The model sees a frozen context; older messages are labeled separately and do not replace the current input. Conversation content stays in `sessionStorage` for the current tab, including navigation between pages. Clear conversation removes it; the server does not persist chats. Provider retention is controlled by the configured gateway, not by OptiLens.

Replies distinguish actual execution evidence, source analysis and unverified suggestions. Only native run events establish that a transformation was recorded. No event does not reveal which guard rejected a match. Imported records retain their unverified provenance. Logical rewrites do not establish performance gains.

## Configure a model gateway

Set these environment variables when starting the Java server:

| Variable | Meaning |
| --- | --- |
| `OPTILENS_ASSISTANT_ENDPOINT` | Complete HTTP(S) Chat Completions endpoint, for example `https://your-gateway.example/v1/chat/completions` |
| `OPTILENS_ASSISTANT_MODEL` | A model identifier supported by that gateway |
| `OPTILENS_ASSISTANT_API_KEY` | Optional bearer credential, held only on the server |
| `OPTILENS_ASSISTANT_TOOLS` | `true` by default; set `false` for an explicit text-only connection |

Supply credentials through the server environment or your existing secret manager. Do not put keys in URLs, committed files, browser storage or chat questions. OptiLens never returns the key through its status API and does not log upstream response bodies. It does not discover or automatically select a model.

The gateway must support `/chat/completions`-style `messages`, `stream: true`, `max_tokens`, SSE `choices[].delta` chunks and a terminal `[DONE]`. Structured mode additionally requires function tools and streamed `tool_calls`. Tool arguments are collected completely, parsed and checked locally; the implementation does not assume the gateway enforces strict JSON Schema. An incompatible gateway fails explicitly: there is no silent retry or silent fallback after partial output. Select text-only mode explicitly if tools are unavailable. That mode has no structured citations or proposal buttons.

The browser connects only to OptiLens. The configured gateway receives the question, bounded recent history, draft SQL/plan/schema, selected run evidence, rule examples and source excerpts. No query data, database connections or arbitrary local files are read. Without endpoint and model configuration, the panel explains how to enable it and all optimizer functionality remains available.

### DeepSeek

Use `https://api.deepseek.com/chat/completions` as the endpoint and `deepseek-flash` as the model. Provide the key only in `OPTILENS_ASSISTANT_API_KEY` on the server. The official DeepSeek endpoint is sent `thinking: {"type": "disabled"}` so streaming tool turns work without storing or replaying private reasoning content. Other compatible gateways retain their own defaults.

The test instance holds its key in the server process environment only; it is not saved in the repository or an environment file. A manually started new server needs the key supplied again. See the [DeepSeek tool-call documentation](https://api-docs.deepseek.com/guides/tool_calls/) for its non-thinking protocol.

## Context, sources and references

The backend rehydrates run evidence from `Jobs` by record ID; client-supplied result snapshots are not execution evidence. Result references contain page, database, run ID, stage index, event index, before/after side and node ID. Clicking one restores that precise view. Draft references include the input version and refuse to target a changed draft. Assistant highlighting uses a dashed purple outline separate from plan difference colors.

The bundled source pack contains 2,290 Java files from:

- Calcite 1.39.0: commit `0b328db78cca67b7b3407e7bcbeb59061862dd40`, `core/src/main/java`.
- StarRocks 4.0.14: commit `7c18dd9dded1587dad24d912dcf8e777daf919f7`, `fe/fe-core/src/main/java/com/starrocks/sql/optimizer`.

Catalog rule IDs map to implementation source files and configuration entry files. Lexical class references help find same-package bases and helper classes; they are retrieval hints, not a complete semantic dependency graph. Generated classes and dependencies outside these roots can be absent. Initial retrieval uses selected rule paths and text matching; the model may make at most four additional read-only searches for helpers. No network retrieval or vector database is used.

Every excerpt retains its original line numbers, commit, full-file SHA-256 and pinned GitHub URL. The pack includes upstream licenses and notices. Source checksums are verified while loading it. Citations must refer to excerpts actually provided in the current turn; invented or unread source IDs are rejected. The source endpoint resolves only bundled IDs, never arbitrary paths or URLs.

Rebuild the pack from clean checkouts at those exact commits:

```bash
python3 tools/build-assistant-sources.py \
  --calcite /path/to/calcite \
  --starrocks /path/to/starrocks
```

The builder reads tracked Git blobs, validates commit IDs and tracked modifications, and writes a reproducible ZIP with stable ordering and timestamps. Normal Maven builds use the checked-in pack and do not fetch upstream source.

## Proposals and application

A proposal contains the complete candidate request and the original input version. Structural checks reject unsupported engines, rule IDs, modes and oversized inputs before a preview appears. It carries a server signature, valid for the current server session and up to 24 hours. Text and tool arguments are never executed as scripts.

Review input changes shows the original input at send time and the proposed input. Validate suggestion checks the signed candidate. Plan validation uses the existing isolated, bounded worker to build the tree and check expressions/types, displaying the initial plan or located errors without creating a run. SQL validation checks the request structure; parsing and optimization take place only when the user runs the candidate.

Apply remains disabled until validation succeeds. Changed input, rule selection, pending editor text or editor revision makes an old proposal stale. Plan Lab applies a candidate as one undoable transaction. Query Lab offers Restore previous input until further edits make restoration stale. Rule Explorer passes the candidate through tab storage into the appropriate Lab, validates it again, and loads the draft without running it or putting the draft in the URL.

The original optimizer remains the only source of verified transformations. Existing restrictions on editable results, including read-only StarRocks results, remain unchanged. Display snapshots are never reverse-parsed into editable plans.

## API and limits

| Endpoint | Contract |
| --- | --- |
| `GET /api/assistant/status` | Enablement, public model name, tool capability, indexed file count and setup message |
| `POST /api/assistant/chat` | `requestId`, `question`, bounded `history`, frozen `context`; returns SSE |
| `POST /api/assistant/cancel` | `requestId`; cancels a pending gateway connection or response |
| `GET /api/assistant/sources/{id}` | Fixed-version source excerpt with provenance |
| `POST /api/assistant/proposals/validate` | Complete signed proposal; returns existing plan validation structure, or SQL structural validation |

SSE events are `context`, `text`, `node`, `source`, `proposal`, `notice`, `error` and `done`. A stream ending without `done` is incomplete. Only complete checked tool calls produce actions. Errors preserve partial text; no automatic retry is made.

Two concurrent conversation tasks use a dedicated executor with no waiting queue. Optimization resources are used only by explicit candidate validation or existing run actions. Each turn has a 120-second deadline, up to five model exchanges (four lookup rounds plus a final response), two proposals, and bounded tool/output sizes. Heartbeats detect disconnected browser streams; Stop and page navigation explicitly cancel the upstream request. Excess concurrency returns HTTP 429 while health and normal page requests remain available.

Inputs are limited to 8,000 question characters, 20 history messages / 80,000 history characters, a 250 KB chat request, a 100 KB proposed optimizer input, and a 450,000-character assembled context. Large selected run snapshots produce a clear request to choose a smaller stage/event. Responses allow 2 MB per gateway exchange and 40,000 text characters per exchange; browser display and tab storage are also bounded. Existing optimizer limits continue to apply independently.

## Validation

`mvn test` covers offline source provenance, untrusted references, signed proposals, isolated plan validation without history, Unicode and split tool streams, truncation, lookup limits, cancellation and concurrent requests using a local mock gateway. Existing SQL and Plan Lab tests remain required.

```bash
npm test
npm run test:assistant
```

The assistant browser suite starts its own Java instance and local mock gateway. Build `target/optilens.jar` first and install the optional Playwright dependencies/browser. Set `OPTILENS_STARROCKS_HOME` to include native StarRocks validation and a subsequent real optimization. Existing `test:browser`, `test:rules`, `test:plan-lab` and `test:starrocks` suites provide regression coverage.

A real model acceptance pass requires a configured gateway. Ask it to explain a recorded transformation, analyze a rule with no recorded change, and propose a candidate input. Review source references and evidence labels, validate/apply the candidate, then run it to collect native evidence. Mock tests establish transport and review behavior; they do not establish model answer quality.

Acceptance run on 2026-10-23: 180 backend tests, 11 JavaScript tests, the assistant browser suite, and the five existing browser/forwarding suites passed. The assistant suite used a mock model gateway plus real Calcite and StarRocks workers. It covered node-reference follow-ups, both engines, source references, cancellation, stale proposals, validation errors, undo/restore and cross-page draft handoff. Rebuilding the source pack reproduced SHA-256 `8ad29cf1097aa24cfccb6c5333f0eacc9f118bab6c0e4537483a291c145eb7a9`. The initial acceptance run had no configured real model; its results established mock-gateway behavior only.

DeepSeek live smoke checks on 2026-10-23 subsequently completed with `deepseek-flash`. Only a synthetic `Values(1, 2) → Union` plan, dummy `demo(id INTEGER)` schema and pinned public Calcite sources were supplied. The transformation answer produced one checked source reference, one checked node reference and one signed candidate proposal; Calcite successfully validated that candidate. A second conversation about a Values-only plan with zero transformations distinguished observed evidence from possible source-level reasons. The model emitted citation-like text in that second answer rather than structured source tool calls; such text remains literal and is not treated as a verified clickable reference. Twelve targeted assistant tests also passed after the DeepSeek protocol adjustment. These are bounded smoke checks, not a guarantee of model answer accuracy.
