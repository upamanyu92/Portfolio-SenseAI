/* PortfolioSense AI – Dashboard JavaScript
   Handles: charts, file upload, job polling, dashboard updates
*/

"use strict";

/* ═══════════════════════════════════════════════════════
   1. CONSTANTS & DEFAULT DATA
   ═══════════════════════════════════════════════════════ */

const POLL_INTERVAL_MS  = 2000;
const MAX_POLL_ATTEMPTS = 60;   // 2 min max
const GAUGE_RADIUS      = 66;
const GAUGE_ARC_DEG     = 270;
const GAUGE_CIRCUMFERENCE = 2 * Math.PI * GAUGE_RADIUS;
const GAUGE_ARC_LENGTH    = (GAUGE_ARC_DEG / 360) * GAUGE_CIRCUMFERENCE; // ~311.02

// Default/illustrative mock data shown on load
const DEFAULT_STATE = {
  healthScore: 78,
  status: "OPTIMIZED",
  allocation: {
    labels: ["IT", "Pharma", "Energy", "Others"],
    data:   [25, 18, 15, 42],
    colors: ["#00d4b8", "#1a9fd4", "#5a6a9a", "#2a3a6a"],
  },
  transparency: {
    labels: ["Equity", "Debt", "Hybrid", "REIT"],
    data:   [52, 28, 12, 8],
    colors: ["#00d4b8", "#1a9fd4", "#7c3aed", "#0f5fa8"],
  },
  pulse: { bullish: 60, neutral: 30, bearish: 10 },
  confidenceScore: 85,
  confBasis: "Based on: 250+ news sources and sector trends.",
  suggestion: "Consider Rebalancing IT Weight",
  recommendations: [
    { ticker: "TCS",      action: "BUY",  reason: "Sector Trend", price: "$186.70" },
    { ticker: "RELIANCE", action: "SELL", reason: "Overvalued",   price: "$169.00" },
    { ticker: "INFY",     action: "BUY",  reason: "Sector Trend", price: "$123.70" },
    { ticker: "REDI",     action: "SELL", reason: "Overvalued",   price: "$153.50" },
  ],
};

/* ═══════════════════════════════════════════════════════
   2. CHART INSTANCES
   ═══════════════════════════════════════════════════════ */

let allocChart     = null;
let transChart     = null;
let pulseChart     = null;

function chartDefaults() {
  Chart.defaults.color = "#6e8da8";
  Chart.defaults.font.family = "Segoe UI, system-ui, sans-serif";
}

function initAllocChart(data) {
  const ctx = document.getElementById("assetAllocChart");
  if (!ctx) return;
  if (allocChart) allocChart.destroy();
  allocChart = new Chart(ctx, {
    type: "doughnut",
    data: {
      labels: data.labels,
      datasets: [{
        data:            data.data,
        backgroundColor: data.colors,
        borderColor:     "rgba(10,20,40,0.8)",
        borderWidth:     3,
        hoverOffset:     6,
      }],
    },
    options: {
      responsive: false,
      cutout: "68%",
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: ctx => ` ${ctx.label}: ${ctx.raw}%`,
          },
        },
      },
      animation: { duration: 700, easing: "easeInOutQuart" },
    },
  });
}

function initTransChart(data) {
  const ctx = document.getElementById("assetTransChart");
  if (!ctx) return;
  if (transChart) transChart.destroy();
  transChart = new Chart(ctx, {
    type: "doughnut",
    data: {
      labels: data.labels,
      datasets: [{
        data:            data.data,
        backgroundColor: data.colors,
        borderColor:     "rgba(10,20,40,0.8)",
        borderWidth:     3,
        hoverOffset:     6,
      }],
    },
    options: {
      responsive: false,
      cutout: "68%",
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: ctx => ` ${ctx.label}: ${ctx.raw}%`,
          },
        },
      },
      animation: { duration: 700, easing: "easeInOutQuart" },
    },
  });
}

function initPulseChart(pulse) {
  const ctx = document.getElementById("pulseChart");
  if (!ctx) return;
  if (pulseChart) pulseChart.destroy();
  pulseChart = new Chart(ctx, {
    type: "bar",
    data: {
      labels: ["Bullish", "Neutral", "Bearish"],
      datasets: [{
        data:            [pulse.bullish, pulse.neutral, pulse.bearish],
        backgroundColor: ["#00d4b8", "#3a5a7a", "#7c3aed"],
        borderRadius:    6,
        borderSkipped:   false,
        maxBarThickness: 48,
      }],
    },
    options: {
      responsive: false,
      plugins: { legend: { display: false }, tooltip: {
        callbacks: { label: ctx => ` ${ctx.raw}%` },
      }},
      scales: {
        x: {
          grid:   { display: false },
          border: { display: false },
          ticks:  { display: false },
        },
        y: {
          display: false,
          min: 0,
          max: 100,
        },
      },
      animation: { duration: 700, easing: "easeInOutQuart" },
    },
  });
}

/* ═══════════════════════════════════════════════════════
   3. GAUGE HELPERS
   ═══════════════════════════════════════════════════════ */

function updateGauge(score) {
  const progress = document.getElementById("gauge-progress");
  const scoreEl  = document.getElementById("gauge-score");
  const fracEl   = document.getElementById("gauge-fraction");
  const statusEl = document.getElementById("gauge-status");

  if (!progress || !scoreEl) return;

  const filled  = (Math.max(0, Math.min(100, score)) / 100) * GAUGE_ARC_LENGTH;
  const rest    = GAUGE_CIRCUMFERENCE - filled;
  progress.setAttribute("stroke-dasharray", `${filled.toFixed(2)} ${rest.toFixed(2)}`);
  scoreEl.textContent = score;
  if (fracEl) fracEl.textContent = `${score} / 100`;

  if (statusEl) {
    if (score >= 70) {
      statusEl.textContent = "OPTIMIZED";
      statusEl.className = "gauge-status optimized";
    } else if (score >= 40) {
      statusEl.textContent = "MODERATE";
      statusEl.className = "gauge-status moderate";
    } else {
      statusEl.textContent = "NEEDS ATTENTION";
      statusEl.className = "gauge-status warning";
    }
  }
}

/* ═══════════════════════════════════════════════════════
   4. LEGEND & LABELS
   ═══════════════════════════════════════════════════════ */

function updateAllocLegend(data) {
  const legend = document.getElementById("alloc-legend");
  if (!legend) return;
  legend.innerHTML = data.labels.map((label, i) =>
    `<li>
      <span class="legend-dot" style="background:${data.colors[i]}"></span>
      ${label}: ${data.data[i]}%
    </li>`
  ).join("");
}

function updatePulseLabels(pulse) {
  const b = document.getElementById("lbl-bullish");
  const n = document.getElementById("lbl-neutral");
  const r = document.getElementById("lbl-bearish");
  if (b) b.textContent = `Bullish: ${pulse.bullish}%`;
  if (n) n.textContent = `Neutral: ${pulse.neutral}%`;
  if (r) r.textContent = `Bearish: ${pulse.bearish}%`;
}

/* ═══════════════════════════════════════════════════════
   5. RECOMMENDATIONS TABLE
   ═══════════════════════════════════════════════════════ */

const SENTIMENT_ICON = { BUY: "🔥", SELL: "❄️" };

function renderRecommendations(recs) {
  const tbody = document.getElementById("recs-tbody");
  const empty = document.getElementById("recs-empty");
  if (!tbody) return;

  if (!recs || recs.length === 0) {
    tbody.innerHTML = "";
    if (empty) empty.hidden = false;
    return;
  }

  if (empty) empty.hidden = true;
  tbody.innerHTML = recs.map(rec => {
    const actionClass = rec.action === "BUY" ? "buy" : "sell";
    const icon        = SENTIMENT_ICON[rec.action] || "";
    const tickerDisp  = rec.action === "BUY"
      ? `<span style="color:var(--buy-color);font-weight:700">${rec.ticker}</span>`
      : `<span style="color:var(--sell-color);font-weight:700">${rec.ticker}</span>`;
    return `
      <tr>
        <td class="ticker-cell">${tickerDisp}</td>
        <td class="action-cell">
          <span class="action-tag ${actionClass}">
            ${rec.action} ${icon}
          </span>
        </td>
        <td class="reason-cell">${escHtml(rec.reason)}</td>
        <td class="price-cell">${escHtml(rec.price)}</td>
        <td>
          <a href="#" class="deep-dive-link" aria-label="Deep dive for ${rec.ticker}">
            Deep-dive
            <svg viewBox="0 0 12 12" width="11" height="11" fill="none"
                 stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <path d="M1 1h10v10M1 11 11 1"/>
            </svg>
          </a>
        </td>
      </tr>`;
  }).join("");
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/* ═══════════════════════════════════════════════════════
   6. DASHBOARD STATE UPDATE
   ═══════════════════════════════════════════════════════ */

function applyState(state) {
  updateGauge(state.healthScore);
  initAllocChart(state.allocation);
  updateAllocLegend(state.allocation);
  initTransChart(state.transparency);
  initPulseChart(state.pulse);
  updatePulseLabels(state.pulse);
  renderRecommendations(state.recommendations);

  const confEl    = document.getElementById("conf-score");
  const basisEl   = document.getElementById("conf-basis");
  const suggEl    = document.getElementById("suggestion-text");

  if (confEl)  confEl.textContent  = `${state.confidenceScore}%`;
  if (basisEl) basisEl.textContent = state.confBasis;
  if (suggEl)  suggEl.textContent  = state.suggestion;
}

/* Map API AnalysisResult → dashboard state */
function analysisToState(result) {
  const score    = result.sentiment_score   ?? 50;
  const conf     = result.confidence_score  ?? 70;
  const tickers  = (result.tickers || []).slice(0, 6);

  // Derive pulse from sentiment
  const bullish = Math.round(score * 0.8);
  const bearish = Math.max(0, Math.round((100 - score) * 0.25));
  const neutral  = Math.max(0, 100 - bullish - bearish);

  // Build recommendations from tickers
  const recs = tickers.map((t, i) => ({
    ticker: t,
    action: score >= 55 ? (i % 3 === 1 ? "SELL" : "BUY") : (i % 3 !== 1 ? "SELL" : "BUY"),
    reason: i % 2 === 0 ? "Sector Trend" : "Overvalued",  // was "Oven Trend" typo
    price:  `$${(100 + i * 17.3).toFixed(2)}`,
  }));

  // Asset allocation labels from tickers, filling to 4 items
  const sectorLabels  = tickers.length > 0
    ? tickers.slice(0, 4).map(t => t.slice(0, 6))
    : DEFAULT_STATE.allocation.labels;
  const sectorCount   = sectorLabels.length;
  const allocData     = distributePercent(sectorCount);
  const allocColors   = ["#00d4b8","#1a9fd4","#5a6a9a","#2a3a6a","#7c3aed","#0f5fa8"];

  return {
    healthScore:    score,
    allocation: {
      labels: sectorLabels,
      data:   allocData,
      colors: allocColors.slice(0, sectorCount),
    },
    transparency: DEFAULT_STATE.transparency,
    pulse:        { bullish, neutral, bearish },
    confidenceScore: conf,
    confBasis:  result.logic_breakdown
      ? `Based on: ${result.data_verifier?.length > 0 ? result.data_verifier.join(", ") + ". " : ""}${result.logic_breakdown.slice(0, 80)}…`
      : DEFAULT_STATE.confBasis,
    suggestion:     result.suggested_move || DEFAULT_STATE.suggestion,
    recommendations: recs.length > 0 ? recs : DEFAULT_STATE.recommendations,
  };
}

function distributePercent(n) {
  if (n <= 0) return [];
  const base = Math.floor(100 / n);
  const arr  = Array(n).fill(base);
  arr[0] += 100 - base * n;
  return arr;
}

/* ═══════════════════════════════════════════════════════
   7. RECENT ACTIVITY LIST
   ═══════════════════════════════════════════════════════ */

const MAX_ACTIVITY = 8;
const activityItems = [];   // { id, filename, status, jobId }

function addActivity(filename, status, jobId) {
  const id = `act-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  activityItems.unshift({ id, filename, status, jobId });
  if (activityItems.length > MAX_ACTIVITY) activityItems.pop();
  renderActivity();
  return id;
}

function updateActivity(id, status) {
  const item = activityItems.find(a => a.id === id);
  if (item) item.status = status;
  renderActivity();
}

function renderActivity() {
  const list  = document.getElementById("activity-list");
  const empty = document.getElementById("activity-empty");
  if (!list) return;

  if (activityItems.length === 0) {
    list.innerHTML = "";
    if (empty) empty.style.display = "";
    return;
  }

  if (empty) empty.style.display = "none";
  list.innerHTML = activityItems.map(item => {
    const ext   = item.filename.split(".").pop().toUpperCase().slice(0, 4);
    const label = statusLabel(item.status);
    const cls   = `status-${item.status.replace(/\s+/g, "-")}`;
    const parsing = item.status === "queued" || item.status === "in_progress" ? "parsing" : "";
    return `
      <li class="activity-item ${parsing}" data-id="${item.id}">
        <div class="activity-icon">${ext}</div>
        <div class="activity-info">
          <div class="activity-name">${escHtml(item.filename)}</div>
          <div class="activity-sub">${escHtml(item.filename)}</div>
        </div>
        <span class="status-badge ${cls}">${label}</span>
      </li>`;
  }).join("");
}

function statusLabel(status) {
  const map = {
    queued:      "Queued",
    in_progress: "Parsing",
    complete:    "Ready",
    failed:      "Failed",
    error:       "Failed",
  };
  return map[status] || status;
}

/* ═══════════════════════════════════════════════════════
   8. JOB POLLING
   ═══════════════════════════════════════════════════════ */

async function pollJob(jobId, activityId) {
  let attempts = 0;
  showProgress("Analyzing your portfolio…");

  const timer = setInterval(async () => {
    attempts++;
    try {
      const res  = await fetch(`/jobs/${encodeURIComponent(jobId)}`);
      const data = await res.json();

      if (data.status === "complete" && data.result) {
        clearInterval(timer);
        updateActivity(activityId, "complete");
        hideProgress();
        const state = analysisToState(data.result);
        applyState(state);
        setLastAnalyzed();
      } else if (data.status === "failed" || data.status === "error") {
        clearInterval(timer);
        updateActivity(activityId, "failed");
        hideProgress();
        console.warn("Job failed:", data.error);
      } else if (attempts >= MAX_POLL_ATTEMPTS) {
        clearInterval(timer);
        updateActivity(activityId, "failed");
        hideProgress();
      }
    } catch (err) {
      console.error("Poll error:", err);
      clearInterval(timer);
      updateActivity(activityId, "failed");
      hideProgress();
    }
  }, POLL_INTERVAL_MS);
}

/* ═══════════════════════════════════════════════════════
   9. FILE UPLOAD
   ═══════════════════════════════════════════════════════ */

async function uploadFile(file) {
  if (!file) return;

  const actId = addActivity(file.name, "queued");
  showProgress("Uploading…");

  const formData = new FormData();
  formData.append("file", file);

  try {
    const res  = await fetch("/analyze", { method: "POST", body: formData });
    const data = await res.json();

    if (!res.ok) {
      updateActivity(actId, "failed");
      hideProgress();
      alert(`Upload failed: ${data.detail || res.statusText}`);
      return;
    }

    updateActivity(actId, "in_progress");
    pollJob(data.job_id, actId);
  } catch (err) {
    updateActivity(actId, "failed");
    hideProgress();
    console.error("Upload error:", err);
    alert("Upload failed. Please check your connection and try again.");
  }
}

/* ═══════════════════════════════════════════════════════
   10. PROGRESS TOAST
   ═══════════════════════════════════════════════════════ */

let progressEl = null;

function ensureProgress() {
  if (progressEl) return;
  progressEl = document.createElement("div");
  progressEl.className = "upload-progress hidden";
  progressEl.innerHTML = `<div class="spinner"></div><span class="upload-progress-text"></span>`;
  document.body.appendChild(progressEl);
}

function showProgress(msg) {
  ensureProgress();
  progressEl.querySelector(".upload-progress-text").textContent = msg;
  progressEl.classList.remove("hidden");
}

function hideProgress() {
  if (progressEl) progressEl.classList.add("hidden");
}

/* ═══════════════════════════════════════════════════════
   11. TIMESTAMP
   ═══════════════════════════════════════════════════════ */

function setLastAnalyzed() {
  const el = document.getElementById("last-analyzed");
  if (el) el.textContent = "just now";

  let mins = 0;
  const interval = setInterval(() => {
    mins++;
    if (!el) { clearInterval(interval); return; }
    el.textContent = `${mins} min${mins > 1 ? "s" : ""} ago`;
  }, 60_000);
}

/* ═══════════════════════════════════════════════════════
   12. DRAG & DROP
   ═══════════════════════════════════════════════════════ */

function initDragDrop() {
  const zone = document.getElementById("upload-drop-zone");
  if (!zone) return;

  zone.addEventListener("dragenter", e => { e.preventDefault(); zone.classList.add("drag-over"); });
  zone.addEventListener("dragover",  e => { e.preventDefault(); });
  zone.addEventListener("dragleave", e => {
    if (!zone.contains(e.relatedTarget)) zone.classList.remove("drag-over");
  });
  zone.addEventListener("drop", e => {
    e.preventDefault();
    zone.classList.remove("drag-over");
    const file = e.dataTransfer?.files?.[0];
    if (file) uploadFile(file);
  });

  // Also click the zone (not the button) to open file picker
  zone.addEventListener("click", e => {
    if (e.target.closest(".select-file-btn") || e.target.id === "file-input") return;
    document.getElementById("file-input")?.click();
  });
}

/* ═══════════════════════════════════════════════════════
   13. INIT
   ═══════════════════════════════════════════════════════ */

document.addEventListener("DOMContentLoaded", () => {
  chartDefaults();

  // Render default state
  applyState(DEFAULT_STATE);

  // File input change
  const fileInput = document.getElementById("file-input");
  if (fileInput) {
    fileInput.addEventListener("change", () => {
      if (fileInput.files?.[0]) {
        uploadFile(fileInput.files[0]);
        fileInput.value = "";   // reset so same file can be re-uploaded
      }
    });
  }

  initDragDrop();
});
