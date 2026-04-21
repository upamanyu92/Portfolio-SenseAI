from typing import List, Optional

from pydantic import BaseModel, Field


class AnalysisResult(BaseModel):
    provider: str = Field(default="fallback")
    tickers: List[str] = Field(default_factory=list)
    sentiment_score: int = Field(ge=0, le=100)
    confidence_score: int = Field(ge=0, le=100)
    logic_breakdown: str
    data_verifier: List[str] = Field(default_factory=list)
    suggested_move: str


class UploadResponse(BaseModel):
    job_id: str
    status: str


class JobStatusResponse(BaseModel):
    job_id: str
    status: str
    result: Optional[AnalysisResult] = None
    error: Optional[str] = None
