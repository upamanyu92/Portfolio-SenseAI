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

Set the `NVIDIA_API_KEY` build environment variable, then open `android/` in Android Studio, or run `./gradlew :app:assembleDebug` from that directory with JDK 17 and Android SDK 36. The Kotlin Compose app supports Android 13 (API 33) and newer.

The dashboard opens with an illustrative portfolio. For built-in analysis, select **Upload statements**. The app reads searchable PDFs, XLSX spreadsheets, and DOCX documents locally, masks common PAN, numeric ID and email patterns, and sends the extracted text to NVIDIA's hosted `nvidia/nemotron-3.5-super-vl-120b-a12b` model. Other personal details can remain. This mode requires internet and model access. Image-only PDFs need a searchable export. The build embeds the provided credential in the APK. Anyone with a copy can extract it.

**Custom server** retains the PortfolioSense HTTPS API, Redis, and ARQ analysis workflow. Existing server configurations remain selected after upgrading. The app retains the latest completed report and eight recent upload batches on the device. Markets displays the repository's illustrative snapshot.

### Net trade planner

From **Dashboard → Net trade plan**, save the example input, replace its synthetic values, then import the JSON. The APK includes Python, NumPy, OpenBLAS, and SciPy. Planning runs locally. **Review plan with NVIDIA** sends the resulting metrics and proposed orders for a separate narrative review. **Export plan JSON** saves the numerical record. An ordinary statement can supply position values and identifiers. Verified constituents, prices, lots, market assumptions and the fiscal-year ledger generally need a separate planning input.

Use fractions for weights, annual rates and transaction costs. Supply INR prices and values, current portfolio cash, trade date, and an annual covariance matrix in the exact order of the positions. Include potential purchases as zero-value positions. Each position has a stable ID, asset type, annual gross expected return, expense ratio, position cap, tradable unit step, signal confidence and, optionally, raw factor scores. Each nonzero tilt coefficient multiplies the cross-sectional standardized factor. Give every holding its FIFO lots, acquisition dates, adjusted cost bases, units, exit loads and applicable lock dates. Each input position represents one FIFO account or folio. ELSS uses a three-year lot lock. A fund may identify a comparable alternative using `switch_to`. Put known cash, debt and other non-stock sleeves in `non_stock_sleeves`. Unidentified residue remains fund-specific, so overlap between incompletely disclosed funds is a lower bound.

The example tax policy illustrates a resident Indian investor scenario. Select the applicable regime for each position and supply year-to-date gains or losses, slab rate and annual exemption. Hybrid fund classification needs explicit verification. The estimator covers the modeled capital-gain buckets only. Cess, surcharge, grandfathering adjustments, special taxpayer rules and complete multi-year loss carryforwards require separate treatment. Input the applicable tax basis and aggregate friction rates for the account.

The optimizer uses one-year expected excess return minus a variance penalty, incremental FIFO tax, transaction costs, and a near-anniversary sale penalty. Cash is the residual allocation and funds estimated taxes and fees. Turnover sums absolute security trades relative to starting NAV. Final weights use post-cost NAV. Quantities are rounded down to permitted unit steps and tiny trades are suppressed. The engine rechecks cash, concentration, locks, caps, and turnover after rounding. A failed solve or failed discrete constraint can yield no actionable plan. Confidence is an input signal scaled down for incomplete fund disclosure. Projected net gain compares expected one-year wealth with holding the current positions. It is sensitive to the input forecasts.

Run the engine directly with Python, NumPy and SciPy using `from quant.engine import run_plan`. Run the planner regression tests with `python -m unittest discover -s tests -p test_quant.py`. Gradle fetches checksum-pinned runtime assets on the first build. For an offline build, set `PORTFOLIO_RUNTIME_DIR` to a directory containing the files listed in `android/quant-runtime.sha256`.

### Holding prices

Saved statement positions refresh after analysis, on foreground entry and once per minute while the app is open. Holdings also has a manual refresh. The app resolves NSE stocks and listed ETFs against the exchange's security directories, then requests INR quotes from Yahoo Finance. Published NSE closing prices provide a fallback. For other supported Indian mutual funds it reads the latest dated AMFI NAV. Matching uses ISIN when present and otherwise requires an unambiguous exact name or ticker. Each successful quote is stored on the device with the source and market date. The app values the saved units against that price and recomputes unrealized P&L using the original invested amount. Quotes may be delayed. Unsupported positions or unavailable feeds retain their previous valuation. Statement reconciliation and the dated quant scenarios continue to use their original input snapshot.
