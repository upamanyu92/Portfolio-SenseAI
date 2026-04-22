import asyncio
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

# Load .env.local first (local Ollama / dev overrides), then .env
for _env_file in (".env.local", ".env"):
    _p = Path(__file__).parent.parent / _env_file
    if _p.exists():
        for _line in _p.read_text().splitlines():
            _line = _line.strip()
            if not _line or _line.startswith("#"):
                continue
            if "=" in _line:
                _k, _, _v = _line.partition("=")
                os.environ.setdefault(_k.strip(), _v.strip())
        break  # first file found wins

from arq import create_pool
from arq.connections import RedisSettings
from arq.jobs import Job
from fastapi import FastAPI, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from redis.exceptions import RedisError

from app.schemas import JobStatusResponse, UploadResponse

ALLOWED_EXTENSIONS = {".pdf", ".xlsx", ".docx"}

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    redis_url = os.getenv("REDIS_URL")
    app.state.redis = None
    if redis_url:
        try:
            app.state.redis = await create_pool(RedisSettings.from_dsn(redis_url))
        except (RedisError, OSError):
            logger.exception("Failed to connect to Redis at startup; queue endpoints will return 503")
    try:
        yield
    finally:
        redis = getattr(app.state, "redis", None)
        if redis:
            await redis.close()


app = FastAPI(title="PortfolioSense AI", lifespan=lifespan)

_STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=str(_STATIC_DIR)), name="static")


@app.get("/", include_in_schema=False)
async def index() -> FileResponse:
    return FileResponse(str(_STATIC_DIR / "index.html"))


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/analyze", response_model=UploadResponse)
async def analyze(request: Request) -> UploadResponse:
    form = await request.form()
    selected_files: list[UploadFile] = []

    def _is_upload_file(value: object) -> bool:
        return hasattr(value, "filename") and hasattr(value, "read")

    for upload in form.getlist("files"):
        if _is_upload_file(upload):
            selected_files.append(upload)  # type: ignore[arg-type]

    # Backward compatible single-file form field.
    legacy_upload = form.get("file")
    if _is_upload_file(legacy_upload):
        selected_files.append(legacy_upload)  # type: ignore[arg-type]

    if not selected_files:
        raise HTTPException(status_code=400, detail="No files uploaded")

    payload: list[dict[str, object]] = []
    for upload in selected_files:
        extension = Path(upload.filename or "").suffix.lower()
        if extension not in ALLOWED_EXTENSIONS:
            raise HTTPException(status_code=400, detail="Unsupported file type")
        payload.append({
            "filename": upload.filename,
            "file_bytes": await upload.read(),
        })

    redis = getattr(app.state, "redis", None)
    if redis is None:
        raise HTTPException(status_code=503, detail="Queue backend unavailable")

    job = await redis.enqueue_job("process_holding_statement", payload)
    return UploadResponse(
        job_id=job.job_id,
        status="queued",
        file_count=len(payload),
        filenames=[str(item["filename"] or "") for item in payload],
    )


@app.get("/jobs/{job_id}", response_model=JobStatusResponse)
async def job_status(job_id: str) -> JobStatusResponse:
    redis = getattr(app.state, "redis", None)
    if redis is None:
        raise HTTPException(status_code=503, detail="Queue backend unavailable")

    job = Job(job_id, redis)

    try:
        result = await job.result(timeout=0)
        return JobStatusResponse(job_id=job_id, status="complete", result=result)
    except asyncio.TimeoutError:
        status = await job.status()
        return JobStatusResponse(job_id=job_id, status=status.value)
    except Exception as exc:
        return JobStatusResponse(job_id=job_id, status="failed", error=str(exc))
