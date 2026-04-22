import gc
import os
from pathlib import Path
from typing import Any

from arq.connections import RedisSettings

from app.analyzer import get_portfolio_analysis, sanitize_data
from app.parser import extract_holdings, extract_text


async def process_holding_statement(_ctx: dict, file_bytes: Any, filename: str = "") -> dict:
    batch: list[dict[str, Any]]
    # Backward compatibility for jobs that still enqueue positional single-file args.
    if isinstance(file_bytes, bytes):
        batch = [{"file_bytes": file_bytes, "filename": filename}]
    else:
        batch = file_bytes  # type: ignore[assignment]

    combined_holdings: list[dict[str, Any]] = []
    extracted_chunks: list[str] = []

    for item in batch:
        one_bytes = item.get("file_bytes", b"")
        one_name = str(item.get("filename") or "")
        extension = Path(one_name).suffix.lower()

        if not isinstance(one_bytes, (bytes, bytearray)):
            continue

        extracted = extract_text(file_bytes=bytes(one_bytes), extension=extension)
        holdings = extract_holdings(file_bytes=bytes(one_bytes), extension=extension)

        if extracted:
            extracted_chunks.append(extracted)
        if holdings:
            combined_holdings.extend(holdings)

    sanitized = sanitize_data("\n".join(extracted_chunks))
    analysis = await get_portfolio_analysis(
        sanitized,
        holdings=combined_holdings,
        source_count=len(batch),
    )
    analysis["holdings"] = combined_holdings
    gc.collect()
    return analysis


class WorkerSettings:
    redis_settings = RedisSettings.from_dsn(os.getenv("REDIS_URL", "redis://localhost:6379"))
    functions = [process_holding_statement]
    max_jobs = 1
