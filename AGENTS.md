# AGENTS.md

## Big picture
- `app/main.py` is both the FastAPI API and the static site host. `/` serves `app/static/index.html`; `/static` serves the dashboard assets.
- The request path is intentionally thin: `POST /analyze` validates the extension, reads the upload into memory, and enqueues `process_holding_statement` in Redis-backed ARQ. Do not move parsing/analysis into the request handler.
- The async job flow is `app/main.py` → Redis/ARQ → `app/worker.py` → `app/parser.py` → `app/analyzer.py` → `app/schemas.py` response shape.
- `GET /jobs/{job_id}` returns either ARQ job status or the final analysis payload. The frontend in `app/static/app.js` polls this endpoint every 2s and expects statuses like `queued`, `in_progress`, `complete`, `failed`.
- Redis is a hard dependency for queue-backed endpoints: when `REDIS_URL` is missing or unreachable, `app/main.py` returns HTTP 503 for `/analyze` and `/jobs/{job_id}`.

## Parsing and analysis pipeline
- Supported upload types are exactly `.pdf`, `.xlsx`, and `.docx` (`ALLOWED_EXTENSIONS` in `app/main.py`, branching in `app/parser.py`). Unsupported extensions currently return `""` from `extract_text`.
- `app/worker.py` keeps the pipeline simple: `extract_text(...)` + `extract_holdings(...)` → `sanitize_data(...)` → `await get_portfolio_analysis(...)`, then merges `analysis["holdings"] = holdings` and forces `gc.collect()`. Keep long-running or memory-heavy work here.
- `extract_holdings()` in `app/parser.py` only operates on `.xlsx` files (returns `[]` for other types). It auto-detects `instrument_type` as `"mutual_fund"` when headers include `scheme name` or `folio no`; otherwise defaults to `"stock"`. Recognized header aliases are defined inline in `extract_holdings()`.
- `sanitize_data()` in `app/analyzer.py` redacts PAN numbers and 10–12 digit phone/ID values before any cloud call. Preserve this pre-LLM sanitization step when changing prompts or provider logic.
- Cloud analysis is optional. If `CLOUD_LLM_ENDPOINT` or `CLOUD_LLM_API_KEY` is absent (and no Anthropic/Ollama config), the code deliberately falls back to `_fallback_analysis()`. Invalid or malformed provider responses also fall back.
- Three provider modes exist; `provider` is set accordingly so the UI/API can distinguish:
  - `"cloud"` — generic OpenAI-compatible endpoint (`CLOUD_LLM_ENDPOINT` + `CLOUD_LLM_API_KEY`; default model `gemini-1.5-flash`). Response shape must be `{"analysis": {...}}` or inline JSON.
  - `"cloud"` (Anthropic) — activated when `ANTHROPIC_API_KEY` is set and `anthropic.com` appears in endpoint (or no endpoint given). Uses `/v1/messages` format with `content[].text` blocks; default model `claude-3-5-sonnet-latest`.
  - `"ollama"` — activated when `CLOUD_LLM_ENDPOINT` contains `"11434"` or `"ollama"` (no API key required). Uses `/api/generate` with `{"response": "..."}` shape; default model `gemma3:4b`.
  - `"fallback"` — regex-based, no network call. This is a real product behavior, not just a test helper.
- `_extract_analysis_payload()` handles both the legacy `{"analysis": {...}}` cloud shape and Anthropic `content[{type:"text"}]` blocks. `_parse_json_from_text_block()` recovers JSON wrapped in markdown/code fences.
- Keep `AnalysisResult` in `app/schemas.py` aligned with analyzer output: `provider`, `tickers`, `sentiment_score`, `confidence_score`, `logic_breakdown`, `data_verifier`, `suggested_move`, `holdings`. The `Holding` model fields are: `name`, `instrument_type`, `quantity`, `isin` (opt), `category` (opt), `invested_value` (opt), `current_value` (opt), `source` (opt).

## Frontend/API contract
- `app/static/app.js` converts backend analysis into dashboard widgets via `analysisToState()`. The backend should return domain data only; the frontend derives charts, recommendations, and labels.
- Upload UX assumes multipart form upload to `/analyze`, then polling `/jobs/{job_id}` until `complete`; changing endpoint names or status values will break the dashboard.
- The UI ships with illustrative `DEFAULT_STATE`; successful jobs replace it. When debugging end-to-end, verify both upload and poll flows, not just `/health`.

## Developer workflows
- Local setup that works on macOS in this repo:
  `python3 -m venv .venv && source .venv/bin/activate && python -m pip install -r requirements.txt`
- Local app server:
  `export REDIS_URL=redis://localhost:6379 && uvicorn app.main:app --reload`
- Start the worker separately: `arq app.worker.WorkerSettings`
- Run tests with the built-in unittest suite (there is no pytest config in this repo): `python -m unittest discover -s tests`
- Repo-specific gotcha: importing `app.parser` or `app.analyzer` also executes `app/__init__.py`, which re-exports `app.main:app`. Test runs therefore need FastAPI/ARQ dependencies installed, even for parser-only tests.

## Deployment and env
- `.env.example` shows the core env vars: `REDIS_URL`, `CLOUD_LLM_ENDPOINT`, `CLOUD_LLM_API_KEY`, `CLOUD_LLM_MODEL`. `ANTHROPIC_API_KEY` is also supported when using the Anthropic provider.
- `app/main.py` loads `.env.local` first (if present), then `.env`; `.env.local` wins and is never committed. Use it for local Ollama or dev overrides (e.g. point `CLOUD_LLM_ENDPOINT` at `http://localhost:11434/api/generate`).
- `render.yaml` defines three services: web (`gunicorn ... app.main:app`), worker (`arq app.worker.WorkerSettings`), and internal Redis. Keep web/worker changes compatible with that split deployment.
- `WorkerSettings.max_jobs = 1` in `app/worker.py` is intentional; if you raise concurrency, review parser memory usage and job isolation first.

## Codebase-specific guardrails
- Match the existing standard-library `unittest` style in `tests/test_parser.py` and `tests/test_analyzer.py`.
- When changing analyzer behavior, cover `cloud`, `anthropic`, `ollama`, and `fallback` paths; `tests/test_analyzer.py` mocks `httpx.AsyncClient` for cloud and Anthropic cases and tests malformed-response fallback.
- When changing parsing behavior, add fixture-free tests like the current in-memory XLSX/DOCX tests instead of checking in sample documents. The parser tests cover both `extract_text` and `extract_holdings` (stock and mutual-fund variants).
