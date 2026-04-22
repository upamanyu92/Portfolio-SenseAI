/* PortfolioSense AI – app.js
   SPA router, charts, upload/poll, Holdings, Markets, Account, Learn pages.
*/
"use strict";

/* ═══════════════════════════════════════════════
   1. CONSTANTS & DEFAULT DATA
═══════════════════════════════════════════════ */

const POLL_INTERVAL_MS   = 2000;
const MAX_POLL_ATTEMPTS  = 60;
const GAUGE_RADIUS       = 66;
const GAUGE_ARC_DEG      = 270;
const GAUGE_CIRCUMFERENCE = 2 * Math.PI * GAUGE_RADIUS;
const GAUGE_ARC_LENGTH    = (GAUGE_ARC_DEG / 360) * GAUGE_CIRCUMFERENCE;

const DEFAULT_STATE = {
  healthScore: 78,
  allocation:  { labels: ["IT","Pharma","Energy","Others"], data: [25,18,15,42], colors: ["#00d4b8","#1a9fd4","#5a6a9a","#2a3a6a"] },
  transparency:{ labels: ["Equity","Debt","Hybrid","REIT"],  data: [52,28,12,8],  colors: ["#00d4b8","#1a9fd4","#7c3aed","#0f5fa8"] },
  pulse:       { bullish: 60, neutral: 30, bearish: 10 },
  confidenceScore: 85,
  confBasis:   "Based on: 250+ news sources and sector trends.",
  suggestion:  "Consider Rebalancing IT Weight",
  recommendations: [
    { ticker:"TCS",      action:"BUY",  reason:"Sector Trend", price:"₹186.70" },
    { ticker:"RELIANCE", action:"SELL", reason:"Overvalued",   price:"₹169.00" },
    { ticker:"INFY",     action:"BUY",  reason:"Sector Trend", price:"₹123.70" },
    { ticker:"REDI",     action:"SELL", reason:"Overvalued",   price:"₹153.50" },
  ],
  holdings: [
    { name:"INFY",              instrument_type:"stock",       quantity:25,    current_value:3100  },
    { name:"Axis Small Cap Fund",instrument_type:"mutual_fund",quantity:81.773,current_value:9026.1},
  ],
};

/* persisted cross-page state */
let portfolioState = null;   // last successful analysisToState() result
let lastAnalysisResult = null; // raw API result

/* ═══════════════════════════════════════════════
   2. SPA ROUTER
═══════════════════════════════════════════════ */

let currentPage = "dashboard";
let sectorChart = null;
let fiiChart    = null;

function navigateTo(page) {
  if (!page) return;
  currentPage = page;

  document.querySelectorAll(".page-panel").forEach(panel => {
    const show = panel.dataset.page === page;
    panel.hidden = !show;
  });

  document.querySelectorAll(".nav-link[data-nav]").forEach(link => {
    const active = link.dataset.nav === page;
    link.classList.toggle("active", active);
    link.setAttribute("aria-current", active ? "page" : "false");
  });

  if (page === "holdings")  renderHoldingsPage();
  if (page === "markets")   renderMarketsPage();
  if (page === "account")   renderAccountPage();
  if (page === "learn")     renderLearnPage();
}

function initRouter() {
  document.addEventListener("click", e => {
    const el = e.target.closest("[data-nav]");
    if (!el) return;
    if (e.target.tagName === "A") e.preventDefault();
    navigateTo(el.dataset.nav);
  });
}

/* ═══════════════════════════════════════════════
   3. DASHBOARD CHARTS
═══════════════════════════════════════════════ */

let allocChart = null;
let transChart = null;
let pulseChart = null;
let lastUploadedFiles = [];

function chartDefaults() {
  Chart.defaults.color       = "#6e8da8";
  Chart.defaults.font.family = "Segoe UI, system-ui, sans-serif";
}

function initAllocChart(data) {
  const ctx = document.getElementById("assetAllocChart");
  if (!ctx) return;
  if (allocChart) allocChart.destroy();
  allocChart = new Chart(ctx, {
    type: "doughnut",
    data: { labels: data.labels, datasets: [{ data: data.data, backgroundColor: data.colors, borderColor:"rgba(10,20,40,0.8)", borderWidth:3, hoverOffset:6 }] },
    options: { responsive: false, cutout:"68%", plugins:{ legend:{display:false}, tooltip:{ callbacks:{ label: c => ` ${c.label}: ${c.raw}%` } } }, animation:{ duration:700 } },
  });
}

function initTransChart(data) {
  const ctx = document.getElementById("assetTransChart");
  if (!ctx) return;
  if (transChart) transChart.destroy();
  transChart = new Chart(ctx, {
    type: "doughnut",
    data: { labels: data.labels, datasets: [{ data: data.data, backgroundColor: data.colors, borderColor:"rgba(10,20,40,0.8)", borderWidth:3, hoverOffset:6 }] },
    options: { responsive: false, cutout:"68%", plugins:{ legend:{display:false}, tooltip:{ callbacks:{ label: c => ` ${c.label}: ${c.raw}%` } } }, animation:{ duration:700 } },
  });
}

function initPulseChart(pulse) {
  const ctx = document.getElementById("pulseChart");
  if (!ctx) return;
  if (pulseChart) pulseChart.destroy();
  pulseChart = new Chart(ctx, {
    type: "bar",
    data: { labels:["Bullish","Neutral","Bearish"], datasets:[{ data:[pulse.bullish,pulse.neutral,pulse.bearish], backgroundColor:["#00d4b8","#3a5a7a","#7c3aed"], borderRadius:6, borderSkipped:false, maxBarThickness:48 }] },
    options: { responsive:false, plugins:{ legend:{display:false}, tooltip:{ callbacks:{ label:c=>` ${c.raw}%` } } }, scales:{ x:{ grid:{display:false}, border:{display:false}, ticks:{display:false} }, y:{ display:false, min:0, max:100 } }, animation:{duration:700} },
  });
}

/* ═══════════════════════════════════════════════
   4. GAUGE
═══════════════════════════════════════════════ */

function updateGauge(score) {
  const progress = document.getElementById("gauge-progress");
  const scoreEl  = document.getElementById("gauge-score");
  const fracEl   = document.getElementById("gauge-fraction");
  const statusEl = document.getElementById("gauge-status");
  if (!progress || !scoreEl) return;

  const filled = (Math.max(0, Math.min(100, score)) / 100) * GAUGE_ARC_LENGTH;
  const rest   = GAUGE_CIRCUMFERENCE - filled;
  progress.setAttribute("stroke-dasharray", `${filled.toFixed(2)} ${rest.toFixed(2)}`);
  scoreEl.textContent = score;
  if (fracEl)   fracEl.textContent   = `${score} / 100`;
  if (statusEl) {
    if      (score >= 70) { statusEl.textContent = "OPTIMIZED";      statusEl.className = "gauge-status optimized"; }
    else if (score >= 40) { statusEl.textContent = "MODERATE";       statusEl.className = "gauge-status moderate"; }
    else                  { statusEl.textContent = "NEEDS ATTENTION"; statusEl.className = "gauge-status warning"; }
  }
}

/* ═══════════════════════════════════════════════
   5. HELPERS
═══════════════════════════════════════════════ */

function escHtml(str) {
  return String(str)
    .replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");
}

function fmtINR(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "–";
  return "₹" + n.toLocaleString("en-IN", { maximumFractionDigits:2 });
}

function fmtQty(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "–";
  return n % 1 === 0 ? String(n) : n.toFixed(3).replace(/0+$/,"").replace(/\.$/,"");
}

function fmtPct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "–";
  const sign = n >= 0 ? "+" : "";
  return sign + n.toFixed(2) + "%";
}

function pnlClass(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "";
  return n >= 0 ? "pnl-pos" : "pnl-neg";
}

function typeBadge(t) {
  if (t === "stock")       return `<span class="type-badge type-stock">Stock</span>`;
  if (t === "mutual_fund") return `<span class="type-badge type-mf">MF</span>`;
  return `<span class="type-badge">${escHtml(t)}</span>`;
}

function updateAllocLegend(data) {
  const legend = document.getElementById("alloc-legend");
  if (!legend) return;
  legend.innerHTML = data.labels.map((l,i) =>
    `<li><span class="legend-dot" style="background:${data.colors[i]}"></span>${l}: ${data.data[i]}%</li>`
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

/* ═══════════════════════════════════════════════
   6. RECOMMENDATIONS TABLE (dashboard)
═══════════════════════════════════════════════ */

function renderRecommendations(recs) {
  const tbody = document.getElementById("recs-tbody");
  const empty = document.getElementById("recs-empty");
  if (!tbody) return;
  if (!recs || recs.length === 0) { tbody.innerHTML = ""; if (empty) empty.hidden = false; return; }
  if (empty) empty.hidden = true;
  tbody.innerHTML = recs.map(rec => {
    const act = (rec.action || "").toUpperCase();
    const cls  = act === "BUY" ? "buy" : act === "SELL" ? "sell" : "hold";
    const icon = act === "BUY" ? "🔥" : act === "SELL" ? "❄️" : "⏸";
    const col  = act === "BUY" ? "var(--buy-color)" : act === "SELL" ? "var(--sell-color)" : "var(--accent-blue)";
    const pri  = (rec.priority || "medium").toLowerCase();
    return `<tr>
      <td class="ticker-cell"><span style="color:${col};font-weight:700">${escHtml(rec.ticker)}</span></td>
      <td><span class="action-tag ${cls}">${act} ${icon}</span></td>
      <td><span class="priority-tag priority-${pri}">${pri}</span></td>
      <td class="reason-cell">${escHtml(rec.reason)}</td>
    </tr>`;
  }).join("");
}

/* ═══════════════════════════════════════════════
   7. HOLDINGS MINI-TABLE (dashboard sidebar)
═══════════════════════════════════════════════ */

function renderHoldings(holdings) {
  const tbody = document.getElementById("holdings-tbody");
  const empty = document.getElementById("holdings-empty");
  if (!tbody) return;
  if (!holdings || holdings.length === 0) {
    tbody.innerHTML = "";
    if (empty) empty.hidden = false;
    return;
  }
  if (empty) empty.hidden = true;
  tbody.innerHTML = holdings.slice(0, 8).map(h => `
    <tr>
      <td>${escHtml(h.name || "–")}</td>
      <td>${typeBadge(h.instrument_type)}</td>
      <td>${fmtQty(h.quantity)}</td>
      <td>${fmtINR(h.current_value)}</td>
      <td>${fmtINR(h.invested_value)}</td>
    </tr>`).join("");
}

/* ═══════════════════════════════════════════════
   8. DASHBOARD STATE
═══════════════════════════════════════════════ */

function applyState(state) {
  updateGauge(state.healthScore);
  initAllocChart(state.allocation);
  updateAllocLegend(state.allocation);
  initTransChart(state.transparency);
  initPulseChart(state.pulse);
  updatePulseLabels(state.pulse);
  renderRecommendations(state.recommendations);
  renderHoldings(state.holdings);

  const confEl  = document.getElementById("conf-score");
  if (confEl)  confEl.textContent  = `${state.confidenceScore}%`;

  const suggEl  = document.getElementById("suggestion-text");
  if (suggEl)  suggEl.textContent  = state.suggestion;

  renderPortfolioDiagnosis(state.portfolioDiagnosis);
  renderAnalysisDetails(state.logicBreakdown, state.dataVerifiers);
  renderRiskFlags(state.riskFlags);
}

function analysisToState(result) {
  const score    = result.sentiment_score  ?? 50;
  const conf     = result.confidence_score ?? 70;
  const holdings = (result.holdings || []).slice(0, 50);
  const fbTickers = holdings
    .map(h => (h?.name || "").split(/\s+/)[0])
    .filter(Boolean)
    .map(s => s.toUpperCase().replace(/[^A-Z0-9]/g, ""));
  const tickers = (result.tickers && result.tickers.length > 0
    ? result.tickers : fbTickers).slice(0, 6);

  const bullish = Math.round(score * 0.8);
  const bearish = Math.max(0, Math.round((100 - score) * 0.25));
  const neutral = Math.max(0, 100 - bullish - bearish);

  // Prefer real LLM top_actions; fall back to ticker-based generation
  let recs;
  if (result.top_actions && result.top_actions.length > 0) {
    recs = result.top_actions.map(a => ({
      ticker:   a.ticker,
      action:   (a.action || "HOLD").toUpperCase(),
      reason:   a.reason || "",
      priority: a.priority || "medium",
    }));
  } else {
    recs = tickers.map((t, i) => ({
      ticker:   t,
      action:   score >= 55 ? (i % 3 === 1 ? "SELL" : "BUY") : (i % 3 !== 1 ? "SELL" : "BUY"),
      reason:   i % 2 === 0 ? "Sector Trend" : "Overvalued",
      priority: "medium",
    }));
  }

  const sectorLabels = tickers.length > 0
    ? tickers.slice(0, 4).map(t => t.slice(0, 6))
    : DEFAULT_STATE.allocation.labels;
  const allocColors = ["#00d4b8","#1a9fd4","#5a6a9a","#2a3a6a","#7c3aed","#0f5fa8"];

  return {
    healthScore: score,
    allocation: {
      labels: sectorLabels,
      data:   distributePercent(sectorLabels.length),
      colors: allocColors.slice(0, sectorLabels.length),
    },
    transparency:       DEFAULT_STATE.transparency,
    pulse:              { bullish, neutral, bearish },
    confidenceScore:    conf,
    suggestion:         result.suggested_move || DEFAULT_STATE.suggestion,
    portfolioDiagnosis: result.portfolio_diagnosis || null,
    logicBreakdown:     result.logic_breakdown || "",
    dataVerifiers:      result.data_verifier   || [],
    riskFlags:          result.risk_flags      || [],
    recommendations:    recs.length > 0 ? recs : DEFAULT_STATE.recommendations,
    holdings:           holdings.length > 0 ? holdings : DEFAULT_STATE.holdings,
  };
}

function distributePercent(n) {
  if (n <= 0) return [];
  const base = Math.floor(100 / n);
  const arr  = Array(n).fill(base);
  arr[0] += 100 - base * n;
  return arr;
}

/* ═══════════════════════════════════════════════
   AI INSIGHT RENDERERS
═══════════════════════════════════════════════ */

function renderPortfolioDiagnosis(text) {
  const el = document.getElementById("portfolio-diagnosis");
  if (!el) return;
  if (text) {
    el.textContent = text;
    el.removeAttribute("hidden");
  } else {
    el.setAttribute("hidden", "");
  }
}

function renderAnalysisDetails(logicBreakdown, dataVerifiers) {
  const textEl     = document.getElementById("logic-breakdown-text");
  const verifierEl = document.getElementById("verifier-list");
  if (textEl) {
    textEl.textContent = logicBreakdown || "Upload a portfolio statement to see AI analysis.";
  }
  if (verifierEl) {
    if (dataVerifiers && dataVerifiers.length > 0) {
      verifierEl.innerHTML = dataVerifiers.map(v =>
        `<li class="verifier-item">&#10003; ${escHtml(v)}</li>`
      ).join("");
      verifierEl.removeAttribute("hidden");
    } else {
      verifierEl.innerHTML = "";
      verifierEl.setAttribute("hidden", "");
    }
  }
}

function renderRiskFlags(flags) {
  const card = document.getElementById("risk-flags-card");
  const list = document.getElementById("risk-flags-list");
  if (!card || !list) return;
  if (!flags || flags.length === 0) {
    card.setAttribute("hidden", "");
    return;
  }
  card.removeAttribute("hidden");
  list.innerHTML = flags.map((flag, i) => {
    // Heuristic: first flag is usually the most severe
    const severity = i === 0 ? "high" : i === 1 ? "medium" : "low";
    return `<li class="risk-flag-item">
      <span class="risk-flag-dot risk-flag-${severity}"></span>
      <span class="risk-flag-text">${escHtml(flag)}</span>
    </li>`;
  }).join("");
}

/* ═══════════════════════════════════════════════
   LEARN PAGE — personalisation
═══════════════════════════════════════════════ */

// Map topic keys to their human-readable labels and scroll targets
const LEARN_TOPIC_META = {
  sip:           { label: "SIP Basics",              icon: "📈" },
  diversification:{ label: "Diversification",        icon: "🧩" },
  elss:          { label: "ELSS & Tax Saving",       icon: "💰" },
  pnl:           { label: "Understanding P&L",       icon: "📊" },
  etf:           { label: "ETF vs Mutual Fund",      icon: "🏛" },
  rebalancing:   { label: "Portfolio Rebalancing",   icon: "⚖️" },
  psu:           { label: "PSU Stocks & Funds",      icon: "🏗" },
  gold:          { label: "Gold as an Asset Class",  icon: "🥇" },
};

function deriveRelevantTopics(holdings, riskFlags, sentimentScore) {
  const topics = new Set();
  const flags  = (riskFlags || []).join(" ").toLowerCase();
  const hasMF  = holdings.some(h => h.instrument_type === "mutual_fund");
  const hasPnlLoss = holdings.some(h => {
    const pnl = (Number(h.current_value) || 0) - (Number(h.invested_value) || 0);
    return pnl < 0;
  });

  // Mutual fund holders → SIP, ELSS, ETF
  if (hasMF) { topics.add("sip"); topics.add("elss"); topics.add("etf"); }

  // Losses or low sentiment → P&L education, rebalancing
  if (hasPnlLoss || sentimentScore < 50) { topics.add("pnl"); topics.add("rebalancing"); }

  // Concentration risk in flags
  if (flags.match(/concentrat|overweight|heavy|sector/)) topics.add("diversification");

  // Rebalancing recommended by AI
  if (flags.match(/rebalanc/)) topics.add("rebalancing");

  // PSU / Gold in holdings names
  const names = holdings.map(h => (h.name || "").toUpperCase()).join(" ");
  if (names.match(/NTPC|NHPC|ONGC|SBI|BEL|NALCO|PSU/)) topics.add("psu");
  if (names.match(/GOLD|SGB|GOLDBEES/)) topics.add("gold");

  // Always suggest diversification if concentrated portfolio
  if (holdings.length > 0 && holdings.length < 5) topics.add("diversification");

  // If nothing matched, show a generic starter set
  if (topics.size === 0) { topics.add("diversification"); topics.add("pnl"); topics.add("rebalancing"); }

  return [...topics].slice(0, 5);
}

function renderLearnPage() {
  const banner      = document.getElementById("personalized-learn-banner");
  const subtitleEl  = document.getElementById("plearn-subtitle");
  const chipsEl     = document.getElementById("plearn-chips");
  if (!banner || !chipsEl) return;

  const state    = portfolioState;
  const holdings = state ? state.holdings : [];
  if (!state || holdings.length === 0) {
    banner.setAttribute("hidden", "");
    return;
  }

  const topics = deriveRelevantTopics(holdings, state.riskFlags, state.healthScore);
  banner.removeAttribute("hidden");

  if (subtitleEl) {
    subtitleEl.textContent = `Based on your ${holdings.length} holding${holdings.length !== 1 ? "s" : ""}, here are the most relevant topics to grow your returns and manage risk:`;
  }

  chipsEl.innerHTML = topics.map(t => {
    const meta = LEARN_TOPIC_META[t];
    if (!meta) return "";
    return `<button class="plearn-chip" data-topic="${t}">${meta.icon} ${meta.label}</button>`;
  }).join("");

  // Remove any previous highlight
  document.querySelectorAll(".learn-card.plearn-highlight").forEach(el => {
    el.classList.remove("plearn-highlight");
  });
}

/* ═══════════════════════════════════════════════
   9. HOLDINGS PAGE (full)
═══════════════════════════════════════════════ */

let holdingsFilter = "all";
let holdingsSort   = "name";
let holdingsQuery  = "";

function renderHoldingsPage() {
  const holdings = portfolioState ? portfolioState.holdings : DEFAULT_STATE.holdings;
  updateHoldingsSummary(holdings);
  renderHoldingsTable(holdings);
}

function updateHoldingsSummary(holdings) {
  let invested = 0, current = 0, stocks = 0, mf = 0;
  holdings.forEach(h => {
    invested += Number(h.invested_value) || 0;
    current  += Number(h.current_value)  || 0;
    if (h.instrument_type === "stock")       stocks++;
    if (h.instrument_type === "mutual_fund") mf++;
  });
  const pnl = current - invested;

  const set = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  const setPnlEl = (id, val) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = fmtINR(val) + " (" + fmtPct(invested > 0 ? (val/invested)*100 : 0) + ")";
    el.className = "summary-value " + pnlClass(val);
  };

  set("h-total-invested", fmtINR(invested));
  set("h-current-value",  fmtINR(current));
  setPnlEl("h-pnl",       pnl);
  set("h-count",          holdings.length);
  set("h-stocks-count",   stocks);
  set("h-mf-count",       mf);
}

function filteredSortedHoldings(allHoldings) {
  let list = allHoldings.slice();

  // filter by type
  if (holdingsFilter !== "all") {
    list = list.filter(h => h.instrument_type === holdingsFilter);
  }

  // filter by search
  if (holdingsQuery) {
    const q = holdingsQuery.toLowerCase();
    list = list.filter(h =>
      (h.name || "").toLowerCase().includes(q) ||
      (h.isin || "").toLowerCase().includes(q) ||
      (h.category || "").toLowerCase().includes(q)
    );
  }

  // sort
  const pnlOf = h => (Number(h.current_value)||0) - (Number(h.invested_value)||0);
  const pnlPctOf = h => {
    const inv = Number(h.invested_value); if (!inv) return 0;
    return pnlOf(h) / inv * 100;
  };

  const sorters = {
    name:              (a,b) => (a.name||"").localeCompare(b.name||""),
    current_value_desc:(a,b) => (Number(b.current_value)||0) - (Number(a.current_value)||0),
    current_value_asc: (a,b) => (Number(a.current_value)||0) - (Number(b.current_value)||0),
    pnl_desc:          (a,b) => pnlOf(b) - pnlOf(a),
    pnl_asc:           (a,b) => pnlOf(a) - pnlOf(b),
    pnl_pct_desc:      (a,b) => pnlPctOf(b) - pnlPctOf(a),
    quantity_desc:     (a,b) => (Number(b.quantity)||0) - (Number(a.quantity)||0),
  };
  if (sorters[holdingsSort]) list.sort(sorters[holdingsSort]);

  return list;
}

function renderHoldingsTable(allHoldings) {
  const tbody = document.getElementById("holdings-full-tbody");
  const empty = document.getElementById("holdings-full-empty");
  if (!tbody) return;

  const list = filteredSortedHoldings(allHoldings);

  if (list.length === 0) {
    tbody.innerHTML = "";
    if (empty) empty.hidden = false;
    return;
  }
  if (empty) empty.hidden = true;

  tbody.innerHTML = list.map((h, i) => {
    const inv  = Number(h.invested_value) || 0;
    const cur  = Number(h.current_value)  || 0;
    const pnl  = cur - inv;
    const pct  = inv > 0 ? (pnl / inv) * 100 : null;
    const pc   = pnlClass(pnl);
    return `<tr>
      <td class="row-num">${i + 1}</td>
      <td class="holding-name-cell">${escHtml(h.name || "–")}</td>
      <td>${typeBadge(h.instrument_type)}</td>
      <td class="cat-cell">${escHtml(h.category || "–")}</td>
      <td class="isin-cell">${escHtml(h.isin || "–")}</td>
      <td class="num-col">${fmtQty(h.quantity)}</td>
      <td class="num-col">${fmtINR(inv || null)}</td>
      <td class="num-col">${fmtINR(cur || null)}</td>
      <td class="num-col ${pc}">${fmtINR(pnl)}</td>
      <td class="num-col ${pc}">${pct !== null ? fmtPct(pct) : "–"}</td>
      <td class="src-cell">${escHtml(h.source || "–")}</td>
    </tr>`;
  }).join("");
}

function initHoldingsPageControls() {
  document.addEventListener("change", e => {
    if (e.target.id === "holdings-sort") {
      holdingsSort = e.target.value;
      if (currentPage === "holdings") renderHoldingsPage();
    }
  });

  document.addEventListener("input", e => {
    if (e.target.id === "holdings-search") {
      holdingsQuery = e.target.value;
      if (currentPage === "holdings") renderHoldingsPage();
    }
  });

  document.addEventListener("click", e => {
    const tab = e.target.closest(".filter-tab[data-filter]");
    if (!tab) return;
    holdingsFilter = tab.dataset.filter;
    document.querySelectorAll(".filter-tab").forEach(t => {
      t.classList.toggle("active", t === tab);
      t.setAttribute("aria-selected", t === tab ? "true" : "false");
    });
    if (currentPage === "holdings") renderHoldingsPage();
  });
}

/* ═══════════════════════════════════════════════
   10. MARKETS PAGE (illustrative static data)
═══════════════════════════════════════════════ */

const MARKET_DATA = {
  indices: [
    { name:"NIFTY 50",  value:"22,147",  chg:"+0.48%",  up:true  },
    { name:"SENSEX",    value:"73,088",  chg:"+0.51%",  up:true  },
    { name:"NIFTY IT",  value:"35,612",  chg:"-1.23%",  up:false },
    { name:"NIFTY Bank",value:"47,204",  chg:"+0.83%",  up:true  },
    { name:"NIFTY Mid", value:"41,830",  chg:"+0.35%",  up:true  },
    { name:"Gold (MCX)",value:"₹72,450", chg:"+0.92%",  up:true  },
  ],
  sectors: {
    labels:["PSU","Gold","Pharma","Energy","Infra","FMCG","Auto","IT"],
    data:  [ 18.4, 14.2,   9.7,   7.3,    6.1,  -1.2,  -3.4, -8.6],
  },
  gainers: [
    { name:"NATL ALUM", price:"₹371", chg:"+12.1%" },
    { name:"TATA GOLD",  price:"₹14", chg:"+8.7%" },
    { name:"VODAIDEA",   price:"₹8.9",chg:"+5.5%" },
    { name:"NHPC",       price:"₹77", chg:"+4.2%" },
    { name:"NTPC GREEN", price:"₹95", chg:"+2.8%" },
  ],
  losers: [
    { name:"SPACENET",   price:"₹3.3", chg:"-87.5%" },
    { name:"SUZLON",     price:"₹41",  chg:"-47.5%" },
    { name:"SPRIGHT AGRO",price:"₹0.47",chg:"-46.6%"},
    { name:"MONOTYPE",   price:"₹0.39",chg:"-30.4%" },
    { name:"LIC INDIA",  price:"₹766", chg:"-15.3%" },
  ],
  fii: {
    months: ["Oct","Nov","Dec","Jan","Feb","Mar"],
    fii:    [-3200,-1800, 2100, 1600,-2900, 1400],
    dii:    [ 4100, 3200, 1800, 2900, 4300, 2700],
  },
};

function renderMarketsPage() {
  renderIndices();
  renderMovers();
  initSectorChart();
  initFiiChart();
}

function renderIndices() {
  const strip = document.getElementById("indices-strip");
  if (!strip || strip.dataset.rendered) return;
  strip.dataset.rendered = "1";
  strip.innerHTML = MARKET_DATA.indices.map(idx => `
    <div class="index-tile">
      <div class="index-name">${escHtml(idx.name)}</div>
      <div class="index-value">${escHtml(idx.value)}</div>
      <div class="index-chg ${idx.up ? "chg-up" : "chg-dn"}">${escHtml(idx.chg)}</div>
    </div>`).join("");
}

function renderMovers() {
  const g = document.getElementById("gainers-tbody");
  const l = document.getElementById("losers-tbody");
  if (g && !g.dataset.rendered) {
    g.dataset.rendered = "1";
    g.innerHTML = MARKET_DATA.gainers.map(m => `
      <tr>
        <td class="mover-name">${escHtml(m.name)}</td>
        <td class="mover-price">${escHtml(m.price)}</td>
        <td class="mover-chg chg-up">${escHtml(m.chg)}</td>
      </tr>`).join("");
  }
  if (l && !l.dataset.rendered) {
    l.dataset.rendered = "1";
    l.innerHTML = MARKET_DATA.losers.map(m => `
      <tr>
        <td class="mover-name">${escHtml(m.name)}</td>
        <td class="mover-price">${escHtml(m.price)}</td>
        <td class="mover-chg chg-dn">${escHtml(m.chg)}</td>
      </tr>`).join("");
  }
}

function initSectorChart() {
  const ctx = document.getElementById("sectorChart");
  if (!ctx || sectorChart) return;
  const { labels, data } = MARKET_DATA.sectors;
  const colors = data.map(v => v >= 0 ? "#00d4b8" : "#ff5252");
  sectorChart = new Chart(ctx, {
    type: "bar",
    data: { labels, datasets: [{ data, backgroundColor: colors, borderRadius:4, maxBarThickness:36 }] },
    options: {
      indexAxis: "y",
      responsive: false,
      plugins: { legend:{display:false}, tooltip:{ callbacks:{ label: c => ` ${c.raw > 0 ? "+" : ""}${c.raw}%` } } },
      scales: {
        x: { grid:{ color:"rgba(0,212,184,0.07)" }, border:{display:false}, ticks:{ callback: v => v + "%" } },
        y: { grid:{display:false}, border:{display:false} },
      },
      animation:{ duration:700 },
    },
  });
}

function initFiiChart() {
  const ctx = document.getElementById("fiiChart");
  if (!ctx || fiiChart) return;
  const { months, fii, dii } = MARKET_DATA.fii;
  fiiChart = new Chart(ctx, {
    type: "bar",
    data: {
      labels: months,
      datasets: [
        { label:"FII", data:fii, backgroundColor:"rgba(255,82,82,0.75)", borderRadius:4, maxBarThickness:28 },
        { label:"DII", data:dii, backgroundColor:"rgba(0,212,184,0.75)", borderRadius:4, maxBarThickness:28 },
      ],
    },
    options: {
      responsive: false,
      plugins: { legend:{ labels:{ color:"#6e8da8", boxWidth:12 } }, tooltip:{ callbacks:{ label: c => ` ${c.dataset.label}: ₹${c.raw} Cr` } } },
      scales: {
        x: { grid:{display:false}, border:{display:false} },
        y: { grid:{ color:"rgba(0,212,184,0.07)" }, border:{display:false}, ticks:{ callback: v => "₹" + v } },
      },
      animation:{ duration:700 },
    },
  });
}

/* ═══════════════════════════════════════════════
   11. ACCOUNT PAGE
═══════════════════════════════════════════════ */

function renderAccountPage() {
  const holdings = portfolioState ? portfolioState.holdings : [];

  let invested = 0, current = 0, stocks = 0, mf = 0;
  holdings.forEach(h => {
    invested += Number(h.invested_value) || 0;
    current  += Number(h.current_value)  || 0;
    if (h.instrument_type === "stock")       stocks++;
    if (h.instrument_type === "mutual_fund") mf++;
  });

  const set = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };

  set("acc-holdings-count", holdings.length || 0);
  set("acc-stmt-count",     activityItems.length);
  set("acc-last-upload",    activityItems[0]?.filename || "–");
  set("acc-invested",       fmtINR(invested) || "–");
  set("acc-current",        fmtINR(current)  || "–");

  const pnlEl = document.getElementById("acc-pnl");
  if (pnlEl) {
    const pnl = current - invested;
    pnlEl.textContent = fmtINR(pnl);
    pnlEl.className = "psv " + pnlClass(pnl);
  }

  set("acc-stocks", stocks || "–");
  set("acc-mf",     mf     || "–");
  set("acc-provider", lastAnalysisResult?.provider
    ? lastAnalysisResult.provider.charAt(0).toUpperCase() + lastAnalysisResult.provider.slice(1)
    : "–");

  // Statement history list
  const list  = document.getElementById("stmt-history-list");
  const empty = document.getElementById("stmt-history-empty");
  if (list) {
    if (activityItems.length === 0) {
      list.innerHTML = "";
      if (empty) empty.hidden = false;
    } else {
      if (empty) empty.hidden = true;
      list.innerHTML = activityItems.map(item => {
        const ext = item.filename.split(".").pop().toUpperCase().slice(0, 4);
        return `<li class="activity-item">
          <div class="activity-icon">${ext}</div>
          <div class="activity-info">
            <div class="activity-name">${escHtml(item.filename)}</div>
            <div class="activity-sub">Status: ${statusLabel(item.status)}</div>
          </div>
          <span class="status-badge status-${item.status}">${statusLabel(item.status)}</span>
        </li>`;
      }).join("");
    }
  }
}

/* ═══════════════════════════════════════════════
   12. LEARN PAGE — accordion
═══════════════════════════════════════════════ */

function initLearnPage() {
  document.addEventListener("click", e => {
    // Accordion toggle
    const btn = e.target.closest(".learn-toggle");
    if (btn) {
      const card = btn.closest(".learn-card");
      if (!card) return;
      const body  = card.querySelector(".learn-body");
      const open  = btn.getAttribute("aria-expanded") === "true";
      btn.setAttribute("aria-expanded", open ? "false" : "true");
      btn.textContent = open ? "Learn more ↓" : "Show less ↑";
      if (body) body.hidden = open;
      return;
    }

    // Personalized chip click → highlight & expand the matching card
    const chip = e.target.closest(".plearn-chip[data-topic]");
    if (chip) {
      const topic   = chip.dataset.topic;
      const card    = document.querySelector(`.learn-card[data-topic="${topic}"]`);
      if (!card) return;

      // Remove previous highlights
      document.querySelectorAll(".learn-card.plearn-highlight").forEach(el => {
        el.classList.remove("plearn-highlight");
      });
      card.classList.add("plearn-highlight");

      // Expand the body if not already open
      const toggleBtn = card.querySelector(".learn-toggle");
      const body      = card.querySelector(".learn-body");
      if (body && body.hidden) {
        if (toggleBtn) {
          toggleBtn.setAttribute("aria-expanded", "true");
          toggleBtn.textContent = "Show less ↑";
        }
        body.hidden = false;
      }

      card.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  });
}

/* ═══════════════════════════════════════════════
   13. RECENT ACTIVITY
═══════════════════════════════════════════════ */

const MAX_ACTIVITY = 8;
const activityItems = [];

function addActivity(filename, status, jobId) {
  const id = `act-${Date.now()}-${Math.random().toString(36).slice(2,7)}`;
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
    const ext     = item.filename.split(".").pop().toUpperCase().slice(0,4);
    const label   = statusLabel(item.status);
    const parsing = item.status === "queued" || item.status === "in_progress" ? "parsing" : "";
    return `<li class="activity-item ${parsing}" data-id="${item.id}">
      <div class="activity-icon">${ext}</div>
      <div class="activity-info">
        <div class="activity-name">${escHtml(item.filename)}</div>
        <div class="activity-sub">${escHtml(item.filename)}</div>
      </div>
      <span class="status-badge status-${item.status}">${label}</span>
    </li>`;
  }).join("");
}

function statusLabel(s) {
  return { queued:"Queued", in_progress:"Parsing", complete:"Ready", failed:"Failed", error:"Failed" }[s] || s;
}

/* ═══════════════════════════════════════════════
   14. JOB POLLING
═══════════════════════════════════════════════ */

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
        lastAnalysisResult = data.result;
        portfolioState     = analysisToState(data.result);
        applyState(portfolioState);
        setLastAnalyzed();
        if (currentPage !== "dashboard") renderCurrentPage();
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

function renderCurrentPage() {
  if (currentPage === "holdings") renderHoldingsPage();
  if (currentPage === "account")  renderAccountPage();
}

/* ═══════════════════════════════════════════════
   15. FILE UPLOAD
═══════════════════════════════════════════════ */

async function uploadFiles(files) {
  const batch = (files || []).filter(Boolean);
  if (batch.length === 0) return;
  lastUploadedFiles = batch;

  const label = batch.length === 1
    ? batch[0].name
    : `Batch (${batch.length} files): ${batch.map(f => f.name).join(", ")}`;
  const actId    = addActivity(label, "queued");
  showProgress("Uploading…");

  const formData = new FormData();
  batch.forEach(file => formData.append("files", file));

  try {
    const res  = await fetch("/analyze", { method:"POST", body:formData });
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

function hookFileInput(id) {
  const el = document.getElementById(id);
  if (!el) return;
  el.addEventListener("change", () => {
    if (el.files?.length) { uploadFiles(Array.from(el.files)); el.value = ""; }
  });
}

function initAnalyzeBtn() {
  const btn = document.getElementById("strategy-btn");
  if (!btn) return;
  btn.addEventListener("click", () => {
    if (!lastUploadedFiles.length) { alert("Upload at least one portfolio statement first."); return; }
    uploadFiles(lastUploadedFiles);
  });
}

/* ═══════════════════════════════════════════════
   16. PROGRESS TOAST
═══════════════════════════════════════════════ */

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

/* ═══════════════════════════════════════════════
   17. TIMESTAMP
═══════════════════════════════════════════════ */

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

/* ═══════════════════════════════════════════════
   18. DRAG & DROP
═══════════════════════════════════════════════ */

function initDragDrop() {
  const zone = document.getElementById("upload-drop-zone");
  if (!zone) return;
  zone.addEventListener("dragenter", e => { e.preventDefault(); zone.classList.add("drag-over"); });
  zone.addEventListener("dragover",  e => { e.preventDefault(); });
  zone.addEventListener("dragleave", e => { if (!zone.contains(e.relatedTarget)) zone.classList.remove("drag-over"); });
  zone.addEventListener("drop", e => {
    e.preventDefault();
    zone.classList.remove("drag-over");
    const files = Array.from(e.dataTransfer?.files || []);
    if (files.length) uploadFiles(files);
  });
  zone.addEventListener("click", e => {
    if (e.target.closest(".select-file-btn") || e.target.id === "file-input") return;
    document.getElementById("file-input")?.click();
  });
}

/* ═══════════════════════════════════════════════
   19. GLOBAL SEARCH
═══════════════════════════════════════════════ */

function initGlobalSearch() {
  const input = document.getElementById("global-search");
  if (!input) return;
  input.addEventListener("keydown", e => {
    if (e.key !== "Enter") return;
    const q = input.value.trim();
    if (!q) return;
    const searchInput = document.getElementById("holdings-search");
    if (searchInput) searchInput.value = q;
    holdingsQuery = q;
    navigateTo("holdings");
    input.value = "";
  });
}

/* ═══════════════════════════════════════════════
   20. INIT
═══════════════════════════════════════════════ */

document.addEventListener("DOMContentLoaded", () => {
  chartDefaults();
  applyState(DEFAULT_STATE);

  hookFileInput("file-input");
  hookFileInput("file-input-h");
  initDragDrop();
  initAnalyzeBtn();
  initRouter();
  initHoldingsPageControls();
  initLearnPage();
  initGlobalSearch();
});
