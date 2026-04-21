import os
import re
from typing import Any, Dict

import httpx

PAN_PATTERN = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
ID_PATTERN = re.compile(r"\b\d{10,12}\b")
TICKER_PATTERN = re.compile(r"\b[A-Z]{2,10}\b")


def sanitize_data(text: str) -> str:
    text = PAN_PATTERN.sub("[PAN_REDACTED]", text)
    text = ID_PATTERN.sub("[ID_REDACTED]", text)
    return text


def _fallback_analysis(sanitized_text: str) -> Dict[str, Any]:
    tickers = sorted(
        {
            ticker
            for ticker in TICKER_PATTERN.findall(sanitized_text)
            if ticker not in {"PAN", "REDACTED", "ID"}
        }
    )
    confidence = min(100, 40 + len(tickers) * 12)
    sentiment = 55 if tickers else 50

    return {
        "provider": "fallback",
        "tickers": tickers,
        "sentiment_score": sentiment,
        "confidence_score": confidence,
        "logic_breakdown": (
            "Detected possible ticker symbols from the uploaded statement and "
            "applied a conservative baseline sentiment because live market "
            "news was not queried in fallback mode."
        ),
        "data_verifier": [],
        "suggested_move": "Review sector concentration and rebalance into diversified ETFs.",
    }


async def get_portfolio_analysis(sanitized_text: str) -> Dict[str, Any]:
    endpoint = os.getenv("CLOUD_LLM_ENDPOINT")
    api_key = os.getenv("CLOUD_LLM_API_KEY")
    model = os.getenv("CLOUD_LLM_MODEL", "gemini-1.5-flash")

    if not endpoint or not api_key:
        return _fallback_analysis(sanitized_text)

    prompt = (
        "Analyze the following stock holdings. "
        "1. Identify tickers and quantities. "
        "2. Provide a sentiment score (0-100) based on current market trends. "
        "3. Suggest one Better Portfolio move. "
        f"Data: {sanitized_text}"
    )

    payload = {
        "model": model,
        "input": prompt,
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "portfolio_analysis",
                "schema": {
                    "type": "object",
                    "properties": {
                        "tickers": {"type": "array", "items": {"type": "string"}},
                        "sentiment_score": {"type": "integer", "minimum": 0, "maximum": 100},
                        "confidence_score": {"type": "integer", "minimum": 0, "maximum": 100},
                        "logic_breakdown": {"type": "string"},
                        "data_verifier": {"type": "array", "items": {"type": "string"}},
                        "suggested_move": {"type": "string"},
                    },
                    "required": [
                        "tickers",
                        "sentiment_score",
                        "confidence_score",
                        "logic_breakdown",
                        "data_verifier",
                        "suggested_move",
                    ],
                },
            },
        },
    }

    headers = {"Authorization": f"Bearer {api_key}"}

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            response = await client.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            data = response.json()
    except Exception:
        return _fallback_analysis(sanitized_text)

    analysis = data.get("analysis") if isinstance(data, dict) else None
    if not isinstance(analysis, dict):
        return _fallback_analysis(sanitized_text)

    analysis.setdefault("provider", "cloud")
    return analysis
