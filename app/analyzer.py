import logging
import os
import re
import json
from typing import Any, Dict

import httpx

logger = logging.getLogger(__name__)

PAN_CARD_PATTERN = re.compile(r"\b[A-Z]{5}[0-9]{4}[A-Z]\b")
PHONE_OR_ID_PATTERN = re.compile(r"\b\d{10,12}\b")
TICKER_PATTERN = re.compile(r"\b[A-Z]{2,10}\b")
REQUIRED_ANALYSIS_KEYS = {
    "tickers",
    "sentiment_score",
    "confidence_score",
    "logic_breakdown",
    "data_verifier",
    "suggested_move",
}


def sanitize_data(text: str) -> str:
    text = PAN_CARD_PATTERN.sub("[PAN_REDACTED]", text)
    text = PHONE_OR_ID_PATTERN.sub("[ID_REDACTED]", text)
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
        "portfolio_diagnosis": None,
        "risk_flags": ["Running in offline fallback mode — live market data unavailable"],
        "top_actions": [],
    }


def _is_valid_analysis(analysis: Any) -> bool:
    return isinstance(analysis, dict) and REQUIRED_ANALYSIS_KEYS.issubset(analysis.keys())


def _parse_json_from_text_block(text: str) -> Any:
    text = (text or "").strip()
    if not text:
        return None

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Some providers wrap JSON in markdown/code fences; recover the first object.
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None

    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None


def _extract_analysis_payload(data: Any) -> Dict[str, Any] | None:
    if not isinstance(data, dict):
        return None

    # Legacy/compatible cloud shape.
    analysis = data.get("analysis")
    if _is_valid_analysis(analysis):
        return analysis

    # Anthropic /v1/messages text blocks.
    content = data.get("content")
    if isinstance(content, list):
        for block in content:
            if not isinstance(block, dict) or block.get("type") != "text":
                continue
            parsed = _parse_json_from_text_block(block.get("text", ""))
            if isinstance(parsed, dict):
                if _is_valid_analysis(parsed):
                    return parsed
                nested = parsed.get("analysis")
                if _is_valid_analysis(nested):
                    return nested

    return None


def _is_ollama_endpoint(endpoint: str) -> bool:
    return "11434" in endpoint or "ollama" in endpoint.lower()


def _extract_ollama_text(data: Any) -> str | None:
    """Extract the generated text from an Ollama /api/generate response."""
    if isinstance(data, dict):
        # /api/generate: {"response": "...", "done": true}
        if "response" in data:
            return str(data["response"])
        # /api/chat: {"message": {"role": "assistant", "content": "..."}}
        msg = data.get("message")
        if isinstance(msg, dict) and "content" in msg:
            return str(msg["content"])
    return None


def _compact_text_for_prompt(text: str, max_chars: int = 3500) -> str:
    """Deduplicate noisy extracted lines before sending to the LLM."""
    lines = [ln.strip() for ln in (text or "").splitlines() if ln.strip()]
    seen: set[str] = set()
    unique_lines: list[str] = []

    for line in lines:
        key = re.sub(r"\s+", " ", line.lower())
        if key in seen:
            continue
        seen.add(key)
        unique_lines.append(re.sub(r"\s+", " ", line))

    compact = "\n".join(unique_lines)
    return compact[:max_chars]


def _compact_holdings_for_prompt(holdings: list[dict[str, Any]], max_items: int = 80) -> str:
    """Serialize holdings into a short, token-efficient line format."""
    merged: dict[str, dict[str, Any]] = {}
    for holding in holdings:
        key = f"{str(holding.get('name') or '').strip().upper()}|{holding.get('instrument_type') or ''}"
        if not key or key == "|":
            continue

        existing = merged.get(key)
        if existing is None:
            merged[key] = {
                "name": str(holding.get("name") or "").strip(),
                "instrument_type": holding.get("instrument_type") or "",
                "quantity": float(holding.get("quantity") or 0),
                "invested_value": float(holding.get("invested_value") or 0),
                "current_value": float(holding.get("current_value") or 0),
                "category": str(holding.get("category") or "").strip(),
            }
            continue

        existing["quantity"] += float(holding.get("quantity") or 0)
        existing["invested_value"] += float(holding.get("invested_value") or 0)
        existing["current_value"] += float(holding.get("current_value") or 0)

    compact_rows: list[str] = []
    for item in list(merged.values())[:max_items]:
        category = item["category"] if item["category"] else "-"
        compact_rows.append(
            "|".join(
                [
                    str(item["name"]),
                    str(item["instrument_type"]),
                    f"q={item['quantity']:.4f}",
                    f"inv={item['invested_value']:.2f}",
                    f"cur={item['current_value']:.2f}",
                    f"cat={category}",
                ]
            )
        )

    return "\n".join(compact_rows)


def _build_compact_analysis_input(
    sanitized_text: str,
    holdings: list[dict[str, Any]] | None,
    source_count: int,
) -> str:
    holdings = holdings or []
    holdings_blob = _compact_holdings_for_prompt(holdings)
    text_blob = _compact_text_for_prompt(sanitized_text)

    parts = [
        f"sources={source_count}",
        f"holdings_count={len(holdings)}",
    ]
    if holdings_blob:
        parts.append("holdings_compact:\n" + holdings_blob)
    if text_blob:
        parts.append("text_excerpt:\n" + text_blob)
    return "\n\n".join(parts)


async def get_portfolio_analysis(
    sanitized_text: str,
    holdings: list[dict[str, Any]] | None = None,
    source_count: int = 1,
) -> Dict[str, Any]:
    endpoint = os.getenv("CLOUD_LLM_ENDPOINT")
    api_key = os.getenv("CLOUD_LLM_API_KEY")
    anthropic_api_key = os.getenv("ANTHROPIC_API_KEY")

    use_anthropic = bool(anthropic_api_key and (not endpoint or "anthropic.com" in endpoint))
    use_ollama = bool(endpoint and _is_ollama_endpoint(endpoint))

    if use_anthropic:
        endpoint = endpoint or "https://api.anthropic.com/v1/messages"
        model = os.getenv("CLOUD_LLM_MODEL", "claude-3-5-sonnet-latest")
    elif use_ollama:
        model = os.getenv("CLOUD_LLM_MODEL", "gemma3:4b")
    else:
        model = os.getenv("CLOUD_LLM_MODEL", "gemini-1.5-flash")

    if not endpoint or (not api_key and not anthropic_api_key and not use_ollama):
        return _fallback_analysis(sanitized_text)

    compact_input = _build_compact_analysis_input(
        sanitized_text=sanitized_text,
        holdings=holdings,
        source_count=max(1, source_count),
    )

    json_instruction = (
        "Return ONLY valid JSON. Required keys: "
        "tickers (array strings, max 8), sentiment_score (0-100 int), confidence_score (0-100 int), "
        "logic_breakdown (short, <=70 words), data_verifier (array strings, max 3), suggested_move (short string). "
        "Also include: portfolio_diagnosis (<=30 words), risk_flags (array strings, max 5), "
        "top_actions (array max 6 objects with ticker, action BUY/SELL/HOLD, reason <=12 words, priority high/medium/low)."
    )

    prompt = (
        "You are a portfolio analyst. Prioritize accuracy with concise output. "
        "Use only provided data. If uncertain, lower confidence_score. "
        "Focus on concentration risk, loss control, and high-probability improvements.\n\n"
        f"DATA:\n{compact_input}\n\n{json_instruction}"
    )

    if use_ollama:
        payload = {
            "model": model,
            "prompt": prompt,
            "stream": False,
            "format": "json",
        }
        headers = {"content-type": "application/json"}
    elif use_anthropic:
        payload = {
            "model": model,
            "max_tokens": 450,
            "messages": [{"role": "user", "content": prompt}],
        }
        headers = {
            "x-api-key": anthropic_api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        }
    else:
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
                            "portfolio_diagnosis": {"type": "string"},
                            "risk_flags": {"type": "array", "items": {"type": "string"}},
                            "top_actions": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "ticker":   {"type": "string"},
                                        "action":   {"type": "string"},
                                        "reason":   {"type": "string"},
                                        "priority": {"type": "string"},
                                    },
                                },
                            },
                        },
                        "required": sorted(REQUIRED_ANALYSIS_KEYS),
                    },
                },
            },
        }
        headers = {"Authorization": f"Bearer {api_key}"}

    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            response = await client.post(endpoint, json=payload, headers=headers)
            response.raise_for_status()
            data = response.json()
    except Exception as exc:
        logger.warning("LLM call failed (%s), using fallback", exc)
        return _fallback_analysis(sanitized_text)

    # Ollama returns raw text in "response" field; parse its JSON content
    if use_ollama:
        raw_text = _extract_ollama_text(data)
        if raw_text:
            parsed = _parse_json_from_text_block(raw_text)
            if _is_valid_analysis(parsed):
                parsed.setdefault("provider", "ollama")
                return parsed
        return _fallback_analysis(sanitized_text)

    analysis = _extract_analysis_payload(data)
    if not analysis:
        return _fallback_analysis(sanitized_text)

    analysis.setdefault("provider", "cloud")
    return analysis
