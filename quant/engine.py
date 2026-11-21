"""One-year INR portfolio planner. Rates are scenario inputs; all weights are fractions."""

from calendar import monthrange
from datetime import date
from math import isfinite


def number(value, label, low=None, high=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value):
        raise ValueError(f"{label}: a finite number is required")
    if low is not None and value < low or high is not None and value > high:
        raise ValueError(f"{label}: outside [{low}, {high}]")
    return float(value)


def fraction(value, label):
    return number(value, label, 0, 1)


def anniversary(day, months):
    year, month = divmod(day.year * 12 + day.month - 1 + months, 12)
    return date(year, month + 1, min(day.day, monthrange(year, month + 1)[1]))


def exposure(positions, weights, matrix):
    out = {}
    for p, w in zip(positions, weights):
        for security, constituent in matrix[p["asset_id"]].items():
            out[security] = out.get(security, 0.) + float(w) * constituent
    return dict(sorted(out.items(), key=lambda item: (-item[1], item[0])))


def mapping(positions):
    matrix, funds, missing = {}, [], []
    for p in positions:
        aid = p["asset_id"]
        if p["asset_type"] == "STOCK":
            matrix[aid] = {aid: 1.}
            continue
        funds.append(aid)
        row = {s: fraction(w, f"{aid}/{s}") for s, w in p.get("constituents", {}).items()}
        if any(not s or s.startswith("__") for s in row):
            raise ValueError("Constituent identifiers cannot be blank or start with __")
        residue = 1 - sum(row.values())
        if residue < -1e-9:
            raise ValueError(f"{aid}: constituents exceed 100%")
        if residue > 1e-9:
            row["__unallocated:" + aid] = residue
            missing.append(aid)
        matrix[aid] = row
    overlap = {a: {b: sum(min(w, matrix[b].get(s, 0)) for s, w in matrix[a].items())
                   for b in funds} for a in funds}
    return matrix, overlap, missing


def ledger_tax(g, policy):
    # Short-term losses may offset either term; long-term losses only the long term.
    short, long, slab, other_long = map(float, g)
    # Use losses against higher-rate eligible gains first. Preserve the equity exemption.
    st_loss = -min(short, 0.) - min(slab, 0.)
    lt_loss = -min(long, 0.) - min(other_long, 0.)
    gains = [max(short, 0.), max(long, 0.), max(slab, 0.), max(other_long, 0.)]
    rates = [policy["short_rate"], policy["long_rate"], policy["slab_rate"], policy["long_rate"]]
    for eligible, loss in [([3, 1], lt_loss), ([0, 2, 3, 1], st_loss)]:
        for i in sorted(eligible, key=lambda k: -rates[k]):
            offset = min(loss, max(0., gains[i] - (policy["annual_exemption"] if i == 1 else 0.)))
            gains[i] -= offset
            loss -= offset
    gains[1] = max(0., gains[1] - policy["annual_exemption"])
    return sum(g * r for g, r in zip(gains, rates))


def prepare_lots(p, as_of):
    aid, regime = p["asset_id"], p["tax_regime"]
    if regime not in {"equity_stt", "specified_debt", "other_long_24m"}:
        raise ValueError(f"{aid}: supply an explicit supported tax_regime")
    lots, blocked = [], False
    for lot in sorted(p["lots"], key=lambda x: x["acquired"]):
        bought = date.fromisoformat(lot["acquired"])
        if bought > as_of:
            raise ValueError(f"{aid}: future acquisition date")
        if regime == "specified_debt" and bought < date(2023, 4, 1):
            raise ValueError(f"{aid}: validate the pre-April-2023 lot's regime")
        qty = number(lot["units"], "lot units", 0)
        cost = number(lot["basis"], "lot basis", 0)
        expiry = date.fromisoformat(lot["locked_until"]) if lot.get("locked_until") else bought
        if p.get("elss", False):
            expiry=max(expiry, anniversary(bought,36))
        # Conservative FIFO within one account/folio. Do not jump a blocked lot.
        blocked |= as_of < expiry or lot.get("locked_in", False)
        end = anniversary(bought, 12 if regime == "equity_stt" else 24)
        is_long = regime != "specified_debt" and as_of > end
        bucket = (1 if regime == "equity_stt" else 3) if is_long else (0 if regime == "equity_stt" else 2)
        load = fraction(lot["exit_load"], "exit load")
        if lot.get("exit_load_until") and as_of > date.fromisoformat(lot["exit_load_until"]):
            load = 0.
        lots.append((qty, cost, blocked, bucket, load,
                     regime == "equity_stt" and 0 <= (end - as_of).days < 30))
    if abs(sum(x[0] for x in lots) * p["price"] - p["market_value"]) > .02:
        raise ValueError(f"{aid}: lot units × current price must reconcile to market value")
    return lots


def sell(lots, price, units, policy):
    gains, load, wait = [0., 0., 0., 0.], 0., 0.
    remaining = units
    for qty, basis, blocked, bucket, rate, near in lots:
        if blocked or remaining <= 1e-9:
            break
        take = min(qty, remaining)
        gain = take * (price - basis)
        gains[bucket] += gain
        load += take * price * rate
        if near:
            wait += max(0., gain) * max(0., policy["short_rate"] - policy["long_rate"])
        remaining -= take
    if remaining * price > .02:
        raise ValueError("Sale exceeds unlocked FIFO units")
    return gains, load, wait


def run_plan(data):
    """Return diagnostics for partial statements; issue orders only from fully specified scenarios."""
    import numpy as np
    from scipy.optimize import minimize

    ps = data["positions"]
    if not isinstance(ps, list) or not 1 <= len(ps) <= 80:
        raise ValueError("Supply between 1 and 80 positions, including zero-value candidates")
    ids = [p["asset_id"] for p in ps]
    if any(not isinstance(k, str) or not k or k.startswith("__") for k in ids) or len(set(ids)) != len(ids):
        raise ValueError("Unique asset IDs are required")
    if any(p["asset_type"] not in {"STOCK", "MF_EQUITY", "MF_DEBT", "MF_HYBRID", "MF_UNCLASSIFIED"} for p in ps):
        raise ValueError("Unsupported asset type")
    cash = number(data.get("cash", 0), "cash", 0)
    values = np.array([number(p["market_value"], "market value", 0) for p in ps])
    total = float(sum(values) + cash)
    if total <= 0:
        raise ValueError("Total portfolio value must be positive")
    current = values / total
    matrix, overlaps, unknown = mapping(ps)
    look = exposure(ps, current, matrix)
    look["__cash"] = cash / total
    cap = fraction(data.get("stock_cap", .10), "stock_cap")
    sleeves = set(data.get("non_stock_sleeves", []))
    capped = [s for s in look if not s.startswith("__") and s not in sleeves]
    missing, drag = [], {}
    for p in ps:
        if p["asset_type"].startswith("MF"):
            er = p.get("expense_ratio")
            drag[p["asset_id"]] = None if er is None else p["market_value"] * fraction(er, "expense ratio")
    direct = {p["asset_id"]: float(w) for p,w in zip(ps,current) if p["asset_type"] == "STOCK" and w}
    out = dict(status="inputs_required", lookthrough=look, overlap=overlaps,
               concentration={s: look[s] for s in capped if look[s] > cap + 1e-9},
               direct_fund_duplicates={s: {"direct": w, "through_funds": look[s]-w}
                                       for s,w in direct.items() if look[s]-w > 1e-9},
               unknown_residue=unknown, annual_expense_drag=drag, actions=[], missing=missing)
    required = ("as_of","covariance","tax_policy")
    missing.extend(k for k in required if data.get(k) is None)
    for p in ps:
        fields = ["price","expected_return","max_weight","lots","tax_regime","unit_step"]
        if p["asset_type"] != "STOCK":
            fields.append("expense_ratio")
        missing.extend(p["asset_id"] + "." + k for k in fields if p.get(k) is None)
    out["stock_cap"]=cap
    if missing:
        return out

    as_of = date.fromisoformat(data["as_of"])
    policy = data["tax_policy"]
    for k in ("short_rate","long_rate","slab_rate"):
        fraction(policy[k], k)
    number(policy["annual_exemption"], "annual exemption", 0)
    year = np.array([number(policy[k], k) for k in ("prior_equity_short","prior_long","prior_slab_short")] + [number(policy.get("prior_other_long",0),"prior_other_long")])
    baseline_tax = ledger_tax(year, policy)
    lots, available = [], []
    for p in ps:
        if number(p["price"], "price", 0) == 0 or number(p["unit_step"], "unit step", 0) == 0:
            raise ValueError("Price and unit step must be positive")
        if p["asset_type"] == "STOCK" and p["unit_step"] % 1:
            raise ValueError("Stock lot sizes must be whole units")
        fraction(p.get("signal_confidence",.5),"signal_confidence")
        batch = prepare_lots(p, as_of)
        lots.append(batch)
        available.append(sum(x[0] for x in batch if not x[2]) * p["price"] / total)
    cov = np.array(data["covariance"], dtype=float)
    n = len(ps)
    if cov.shape != (n,n) or not np.isfinite(cov).all() or not np.allclose(cov,cov.T) or np.linalg.eigvalsh(cov).min() < -1e-10:
        raise ValueError("Expected an annual positive semidefinite covariance matrix in position order")
    er = np.array([fraction(p.get("expense_ratio",0), "ER") for p in ps])
    mu = np.array([number(p["expected_return"],"annual expected return") for p in ps]) - er
    for key in ("fundamental","momentum","sentiment"):
        coefficient = number(data.get("tilts",{}).get(key,0),key)
        if coefficient:
            v = np.array([number(p[key], key) for p in ps])
            if v.std() > 1e-10:
                mu += coefficient * (v-v.mean())/v.std()
    maximum = np.array([fraction(p["max_weight"],"max_weight") for p in ps])
    turnover = number(data.get("max_turnover",.35), "turnover", 0, 2)
    minimum = number(data.get("min_trade_value",2500), "minimum trade", 0)
    risk = number(data.get("risk_aversion",3), "risk aversion", 0)
    cash_rate = number(data.get("cash_return",0), "cash return")
    buy_rate = np.array([fraction(p.get("buy_rate",.001),"buy_rate") for p in ps])
    sell_rate = np.array([fraction(p.get("sell_rate",.001),"sell_rate") for p in ps])
    prices = np.array([p["price"] for p in ps])
    steps = np.array([p["unit_step"] for p in ps])
    mapping_matrix = np.array([[matrix[a].get(s,0) for a in ids] for s in capped]).reshape((-1,n))
    lower = np.maximum(0,current-available)

    def costs(delta):
        gains = year.copy()
        taxes, fees = np.zeros(n), np.maximum(delta,0)*buy_rate + np.maximum(-delta,0)*sell_rate
        wait = 0.
        for i in np.flatnonzero(delta < -1e-8):
            prev = max(baseline_tax,ledger_tax(gains,policy))
            g, load, near = sell(lots[i],prices[i],min(-delta[i]/prices[i],available[i]*total/prices[i]),policy)
            gains += g
            taxes[i] = max(baseline_tax,ledger_tax(gains,policy))-prev
            fees[i] += load
            wait += near
        return taxes, fees, wait

    def advantage(w):
        delta = (w-current)*total
        taxes,fees,wait = costs(delta)
        return float((w-current)@(mu-cash_rate) - risk/2*(w@cov@w-current@cov@current)
                     - (taxes.sum()+fees.sum()+wait)/total)

    def budget(w):
        taxes,fees,_ = costs((w-current)*total)
        return 1-sum(w)-(sum(taxes)+sum(fees))/total

    def constraints(w):
        net_nav = budget(w) + sum(w)
        return np.r_[budget(w), turnover-np.abs(w-current).sum(), cap*net_nav-mapping_matrix@w, maximum*net_nav-w]

    def valid(w):
        return min(constraints(w)) >= -1e-8 and all(w>=lower-1e-8) and all(w<=maximum+1e-8)

    out["switch_comparisons"] = []
    for i,p in enumerate(ps):
        if "switch_to" not in p:
            continue
        j=ids.index(p["switch_to"])
        if i==j or any(ps[k]["asset_type"]=="STOCK" for k in (i,j)):
            raise ValueError("A switch must name two different funds")
        amount=available[i]*total
        d=np.zeros(n); d[i]=-amount; d[j]=amount
        tx,fee,_=costs(d)
        gross=amount*max(0., er[i]-er[j])
        out["switch_comparisons"].append(dict(asset_id=ids[i],target_asset_id=ids[j],eligible_value=amount,
            gross_annual_savings=float(gross), est_tax_impact=float(sum(tx)),est_fee_cost=float(sum(fee)),
            first_year_net_savings=float(gross-sum(tx)-sum(fee))))
    for aid, detail in out["direct_fund_duplicates"].items():
        i=ids.index(aid)
        fund_indices=[j for j,p in enumerate(ps) if p["asset_type"]!="STOCK" and matrix[ids[j]].get(aid,0)>0 and current[j]>0]
        if all(cov[j,j]>0 for j in [i]+fund_indices):
            stock_ratio=(mu[i]-cash_rate)/cov[i,i]**.5
            detail["superior_risk_adjusted_return"]=bool(all(stock_ratio>(mu[j]-cash_rate)/cov[j,j]**.5 for j in fund_indices))
    def blocked(reason):
        out.update(status="no_feasible_trades", reason=reason)
        return out

    if any(lower > maximum+1e-9):
        return blocked("Locked holdings exceed an asset cap.")
    bounds=list(zip(lower,maximum))
    solution=minimize(lambda w:-advantage(w), np.clip(current,lower,maximum),
                      bounds=bounds, constraints=[dict(type="ineq",fun=constraints)],
                      method="SLSQP",options=dict(maxiter=300,ftol=1e-10))
    if not solution.success or not valid(solution.x):
        return blocked("No verified continuous solution: "+str(solution.message))
    wanted=(solution.x-current)*total
    units=np.sign(wanted)*np.floor((abs(wanted)+1e-6)/(steps*prices))*steps
    units[abs(units*prices)+1e-7<minimum]=0
    delta=units*prices
    # Discretizing a sale can remove funding. Scale purchases to available net proceeds.
    taxes,fees,_=costs(delta)
    purchases=np.maximum(delta,0)
    budget_buy=cash+sum(np.maximum(-delta,0))-sum(taxes)-sum(fees*(delta<0))
    required=float(purchases@(1+buy_rate))
    if required>max(0,budget_buy)+1e-6:
        scale=max(0,budget_buy)/required
        buys=units>0
        units[buys]=np.floor(units[buys]*scale/steps[buys]+1e-10)*steps[buys]
        units[abs(units*prices)+1e-7<minimum]=0
    delta=units*prices
    w=current+delta/total
    taxes,fees,_=costs(delta)
    reserve=float(sum(taxes)+sum(fees))
    after=total-reserve
    if after<=0:
        return blocked("Trading costs exhaust portfolio value.")
    final=(values+delta)/after
    if (not valid(w) or any(final>maximum+1e-8) or any(mapping_matrix@final>cap+1e-8)):
        if not valid(current):
            return blocked("Unit rounding or minimum order size prevents a funded, cap-compliant trade.")
        units=np.zeros(n);delta=np.zeros(n);w=current.copy()
    if advantage(w)<-1e-10 and valid(current):
        units=np.zeros(n);delta=np.zeros(n);w=current.copy()
    taxes,fees,_=costs(delta)
    reserve=float(sum(taxes)+sum(fees))
    final=(values+delta)/(total-reserve)
    final_cash=max(0.,float(cash-sum(delta)-reserve))
    look_after=exposure(ps,final,matrix)
    look_after["__cash"]=final_cash/(total-reserve)
    actions=[]
    for i,p in enumerate(ps):
        kind="HOLD" if units[i]==0 else "BUY" if units[i]>0 else "EXIT" if values[i]+delta[i]<.01 else "TRIM"
        actions.append(dict(action=kind,asset_id=ids[i],target_asset_id=None,current_weight=float(current[i]),
                            target_weight=float(final[i]),delta_value=float(delta[i]),delta_units=float(units[i]),
                            est_tax_impact=float(taxes[i]),est_fee_cost=float(fees[i]),est_annual_fee_savings=float(-delta[i]*er[i]),
                            confidence_score=int(100*float(p.get("signal_confidence",.5))) if not unknown else int(70*float(p.get("signal_confidence",.5))),
                            rationale=("Hold after costs, constraints and order rounding." if kind=="HOLD" else
                                       "One-year risk-adjusted allocation after FIFO tax and friction; adjusted return "+
                                       f"{mu[i]:.2%}, effective cap {maximum[i]:.2%}.")))
    # Only label one unambiguous comparable sell/buy pair. Preserve each executable leg once.
    sells=[i for i in range(n) if units[i]<0]
    buys=[i for i in range(n) if units[i]>0]
    if len(sells)==len(buys)==1:
        i,j=sells[0],buys[0]
        if ps[i].get("switch_to")==ids[j] and er[j]<er[i] and all(ps[k]["asset_type"].startswith("MF") for k in (i,j)):
            src,dst=actions[i],actions[j]
            paired=min(-delta[i],delta[j])
            saving=paired*(er[i]-er[j])
            upfront=sum(taxes)+sum(fees)
            if saving>upfront:
                src.update(action="SWITCH",target_asset_id=ids[j],buy_leg=dst,
                           est_tax_impact=float(taxes[i]+taxes[j]),est_fee_cost=float(fees[i]+fees[j]),
                           est_annual_fee_savings=float(saving),first_year_net_fee_savings=float(saving-upfront),
                           rationale="Declared lower-cost alternative; matched first-year fee savings exceed both legs' upfront costs.")
                actions.remove(dst)
    out.update(status="ready",actions=actions,target_lookthrough=look_after,cash_after=final_cash,
               upfront_cost=reserve,turnover=float(np.abs(delta).sum()/total),
               expected_net_gain_vs_hold=float(delta@(mu-cash_rate)-reserve*(1+cash_rate)),
               risk_adjusted_advantage=float(advantage(w)*total),annual_fee_change=float(-delta@er),
               adjusted_returns=dict(zip(ids,mu.tolist())))
    return out
