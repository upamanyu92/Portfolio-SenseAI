import gc
import os
from pathlib import Path

from arq.connections import RedisSettings

from app.analyzer import get_portfolio_analysis, sanitize_data
from app.parser import extract_holdings, extract_text


async def process_holding_statement(_ctx: dict, file_bytes: bytes, filename: str) -> dict:
    extension = Path(filename).suffix.lower()
    extracted = extract_text(file_bytes=file_bytes, extension=extension)
    holdings = extract_holdings(file_bytes=file_bytes, extension=extension)
    sanitized = sanitize_data(extracted)
    analysis = await get_portfolio_analysis(sanitized)
    analysis["holdings"] = holdings
    gc.collect()
    return analysis


class WorkerSettings:
    redis_settings = RedisSettings.from_dsn(os.getenv("REDIS_URL", "redis://localhost:6379"))
    functions = [process_holding_statement]
    max_jobs = 1
