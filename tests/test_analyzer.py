import os
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from app.analyzer import get_portfolio_analysis, sanitize_data


class AnalyzerTests(unittest.IsolatedAsyncioTestCase):
    def test_sanitize_data_redacts_pan_and_ids(self) -> None:
        text = "Client ABCDE1234F called from 9876543210"
        sanitized = sanitize_data(text)

        self.assertIn("[PAN_REDACTED]", sanitized)
        self.assertIn("[ID_REDACTED]", sanitized)
        self.assertNotIn("ABCDE1234F", sanitized)
        self.assertNotIn("9876543210", sanitized)

    async def test_fallback_analysis_shape(self) -> None:
        result = await get_portfolio_analysis("HOLDINGS INFY TCS")

        self.assertIn("confidence_score", result)
        self.assertIn("logic_breakdown", result)
        self.assertIn("data_verifier", result)
        self.assertGreaterEqual(result["sentiment_score"], 0)
        self.assertLessEqual(result["sentiment_score"], 100)

    @patch.dict(
        os.environ,
        {
            "CLOUD_LLM_ENDPOINT": "https://example.com/llm",
            "CLOUD_LLM_API_KEY": "secret",
            "CLOUD_LLM_MODEL": "gemini-1.5-flash",
        },
        clear=False,
    )
    @patch("app.analyzer.httpx.AsyncClient")
    async def test_cloud_analysis_path(self, mock_async_client: MagicMock) -> None:
        response = MagicMock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "analysis": {
                "tickers": ["INFY"],
                "sentiment_score": 62,
                "confidence_score": 91,
                "logic_breakdown": "Mocked cloud response",
                "data_verifier": ["Headline A"],
                "suggested_move": "Hold",
            }
        }
        client = AsyncMock()
        client.post.return_value = response
        mock_async_client.return_value.__aenter__.return_value = client

        result = await get_portfolio_analysis("INFY 100")
        self.assertEqual(result["provider"], "cloud")
        self.assertEqual(result["tickers"], ["INFY"])


if __name__ == "__main__":
    unittest.main()
