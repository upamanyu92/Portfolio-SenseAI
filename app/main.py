import asyncio
import os
from pathlib import Path

from arq import create_pool
from arq.connections import RedisSettings
from arq.jobs import Job
from fastapi import FastAPI, File, HTTPException, UploadFile

from app.schemas import JobStatusResponse, UploadResponse

app = FastAPI(title="PortfolioSense AI")
ALLOWED_EXTENSIONS = {".pdf", ".xlsx", ".docx"}


@app.on_event("startup")
async def startup() -> None:
    redis_url = os.getenv("REDIS_URL")
    app.state.redis = None
    if redis_url:
        app.state.redis = await create_pool(RedisSettings.from_dsn(redis_url))


@app.on_event("shutdown")
async def shutdown() -> None:
    redis = getattr(app.state, "redis", None)
    if redis:
        await redis.close()


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/analyze", response_model=UploadResponse)
async def analyze(file: UploadFile = File(...)) -> UploadResponse:
    extension = Path(file.filename or "").suffix.lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail="Unsupported file type")

    redis = getattr(app.state, "redis", None)
    if redis is None:
        raise HTTPException(status_code=503, detail="Queue backend unavailable")

    file_bytes = await file.read()
    job = await redis.enqueue_job("process_holding_statement", file_bytes, file.filename)
    return UploadResponse(job_id=job.job_id, status="queued")


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
