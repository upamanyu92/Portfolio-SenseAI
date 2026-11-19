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

## Android client

Open `android/` in Android Studio, or run `./gradlew :app:assembleDebug` from that directory with JDK 17 and Android SDK 36. The Kotlin Compose app supports Android 13 (API 33) and newer.

The dashboard opens with an illustrative portfolio. For built-in analysis, open **Account → Configure analyzer → NVIDIA**, enter your own NVIDIA API key, then select **Upload statements**. The app reads searchable PDFs, XLSX spreadsheets, and DOCX documents locally, masks common PAN, numeric ID and email patterns, and sends the extracted text to NVIDIA's hosted `nvidia/nemotron-3.5-lightning-30b-a3b` model. Other personal details can remain. This mode requires internet and model access. Image-only PDFs need a searchable export. The Android Keystore protects the locally stored key.

**Custom server** retains the PortfolioSense HTTPS API, Redis, and ARQ analysis workflow. Existing server configurations remain selected after upgrading. The app retains the latest completed report and eight recent upload batches on the device. Markets displays the repository's illustrative snapshot.
