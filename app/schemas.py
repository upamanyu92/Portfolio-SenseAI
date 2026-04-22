from typing import List, Optional

from pydantic import BaseModel, Field


class Holding(BaseModel):
    name: str
    instrument_type: str
    quantity: float
    isin: Optional[str] = None
    category: Optional[str] = None
    invested_value: Optional[float] = None
    current_value: Optional[float] = None
    source: Optional[str] = None


class TopAction(BaseModel):
    """A specific AI-generated action for a single ticker/holding."""
    ticker: str
    action: str  # "BUY" | "SELL" | "HOLD"
    reason: str
    priority: Optional[str] = None  # "high" | "medium" | "low"


class AnalysisResult(BaseModel):
    provider: str = Field(default="fallback")
    tickers: List[str] = Field(default_factory=list)
    sentiment_score: int = Field(ge=0, le=100)
    confidence_score: int = Field(ge=0, le=100)
    logic_breakdown: str
    data_verifier: List[str] = Field(default_factory=list)
    suggested_move: str
    holdings: List[Holding] = Field(default_factory=list)
    # Enriched fields for actionable insights
    portfolio_diagnosis: Optional[str] = None
    risk_flags: List[str] = Field(default_factory=list)
    top_actions: List[TopAction] = Field(default_factory=list)


class UploadResponse(BaseModel):
    job_id: str
    status: str
    file_count: Optional[int] = None
    filenames: List[str] = Field(default_factory=list)


class JobStatusResponse(BaseModel):
    job_id: str
    status: str
    result: Optional[AnalysisResult] = None
    error: Optional[str] = None
