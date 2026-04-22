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


async def get_portfolio_analysis(sanitized_text: str) -> Dict[str, Any]:
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

    json_instruction = (
        "Return ONLY a valid JSON object. Required keys: "
        "tickers (array of strings), sentiment_score (integer 0-100), "
        "confidence_score (integer 0-100), logic_breakdown (string, write at least 3 sentences "
        "explaining your reasoning about the portfolio mix, sector exposure, and market context), "
        "data_verifier (array of strings listing your evidence sources), suggested_move (string). "
        "Also include these enrichment keys for actionable insights: "
        "portfolio_diagnosis (string: 1-2 sentence plain-English executive summary of the portfolio's "
        "health and primary concern, e.g. 'Heavily concentrated in IT; moderate default risk'), "
        "risk_flags (array of strings, up to 5, each a specific risk observable in the holdings, "
        "e.g. 'IT sector overweight at ~45%', 'HDFC Bank showing -18% unrealised loss'), "
        "top_actions (array of up to 6 objects, each with: ticker (string), "
        "action ('BUY'|'SELL'|'HOLD'), reason (string, max 12 words), priority ('high'|'medium'|'low')). "
        "Do not include any explanation outside the JSON."
    )

    prompt = (
        "Analyze the following investment portfolio holdings. "
        "1. Identify ticker symbols and asset names. "
        "2. Provide a sentiment score (0-100) based on the holdings mix. "
        "3. Flag specific portfolio risks (concentration, losses, sector skew). "
        "4. Recommend targeted actions per holding to maximise returns and minimise losses. "
        f"Data:\n{sanitized_text}\n\n{json_instruction}"
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
            "max_tokens": 900,
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
