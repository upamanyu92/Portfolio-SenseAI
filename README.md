# Portfolio-SenseAI

Production-grade asynchronous FastAPI service for parsing portfolio statements (PDF, Excel, Word), redacting sensitive data, and generating sentiment-driven portfolio analysis using a cloud LLM.

## Architecture

- **Web Layer:** FastAPI (`app/main.py`)
- **Worker Layer:** ARQ (`app/worker.py`)
- **Queue/Storage:** Redis
- **Parsing Engine:** `pypdf`, `openpyxl`, `python-docx`
- **Intelligence:** Cloud LLM endpoint (Gemini/Groq compatible) with fallback mode

## Run locally

```bash
pip install -r requirements.txt
export REDIS_URL=redis://localhost:6379
uvicorn app.main:app --reload
```

Start worker:

```bash
arq app.worker.WorkerSettings
```

## API

- `POST /analyze` — upload `.pdf`, `.xlsx`, or `.docx`
- `GET /jobs/{job_id}` — poll asynchronous job status
- `GET /health` — health check

## Deployment

`render.yaml` defines web + worker + internal redis services suitable for Render free tier deployment.
