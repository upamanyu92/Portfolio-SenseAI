import unittest

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


if __name__ == "__main__":
    unittest.main()
