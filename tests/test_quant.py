"""Regressions for model inputs, accounting, FIFO and executable orders."""
import json
import unittest
from datetime import date
from pathlib import Path

from quant.engine import ledger_tax, mapping, prepare_lots, run_plan, sell


def scenario():
    return json.loads((Path(__file__).resolve().parents[1] /
                      "android/app/src/main/assets/quant-example.json").read_text())


class QuantTests(unittest.TestCase):
    def test_residual_and_overlap(self):
        funds = [{"asset_id": n, "asset_type": "MF_EQUITY", "constituents": {"X": w}}
                 for n, w in [("A", .8), ("B", .45)]]
        matrix, overlap, missing = mapping(funds)
        self.assertEqual(set(missing), {"A", "B"})
        self.assertAlmostEqual(sum(matrix["A"].values()), 1.)
        self.assertAlmostEqual(overlap["A"]["B"], .45)
        self.assertAlmostEqual(overlap["A"]["A"], 1.)

    def test_unknown_values_do_not_generate_orders(self):
        report = run_plan({"positions": [
            {"asset_id":"X","asset_type":"STOCK","market_value":500},
            {"asset_id":"F","asset_type":"MF_EQUITY","market_value":500,"constituents":{"X":.8}}]})
        self.assertAlmostEqual(report["lookthrough"]["X"], .9)
        self.assertAlmostEqual(report["direct_fund_duplicates"]["X"]["through_funds"], .4)
        self.assertIsNone(report["annual_expense_drag"]["F"])
        self.assertEqual(report["status"], "inputs_required")
        self.assertEqual(report["actions"], [])

    def test_overallocated_constituents_rejected(self):
        with self.assertRaises(ValueError):
            mapping([{"asset_id":"F","asset_type":"MF_EQUITY","constituents":{"X":.8,"Y":.3}}])

    def test_tax_ledger(self):
        policy = scenario()["tax_policy"]
        self.assertAlmostEqual(ledger_tax([0, 150000, 0, 0], policy), 3125)
        self.assertAlmostEqual(ledger_tax([0, 35000, 0, 10000], policy), 1250)
        self.assertAlmostEqual(ledger_tax([10000, -3000, 0, 0], policy), 2000)
        self.assertAlmostEqual(ledger_tax([-5000, 140000, 0, 0], policy), 1250)

    def test_holding_anniversary_and_fifo(self):
        p = scenario()["positions"][0]
        p["lots"] = [dict(acquired="2025-11-20", units=500, basis=80,exit_load=.01),
                     dict(acquired="2025-01-01", units=500, basis=50,exit_load=0)]
        lots = prepare_lots(p, date(2026,11,20))
        gains, load, near = sell(lots,100,600,scenario()["tax_policy"])
        self.assertEqual(gains, [2000,25000,0,0])
        self.assertEqual(load,100)
        self.assertGreater(near,0)
        self.assertEqual(prepare_lots(p,date(2026,11,21))[1][3],1)

    def test_locked_lots_excluded(self):
        p = scenario()
        p["positions"][0]["elss"] = True
        r = run_plan(p)
        for action in r["actions"]:
            self.assertEqual(action["delta_units"],0)
        self.assertEqual(r["switch_comparisons"][0]["eligible_value"], 0)

    def test_switch_cash_and_cost_reconcile(self):
        r = run_plan(scenario())
        a, = r["actions"]
        self.assertEqual(a["action"], "SWITCH")
        self.assertEqual(a["target_asset_id"],a["buy_leg"]["asset_id"])
        self.assertAlmostEqual(r["cash_after"] + a["delta_value"] +
                               a["buy_leg"]["delta_value"] + r["upfront_cost"], 0, places=6)
        self.assertAlmostEqual(sum(r["target_lookthrough"].values()),1)
        self.assertAlmostEqual(a["first_year_net_fee_savings"],
                               a["est_annual_fee_savings"]-a["est_tax_impact"]-a["est_fee_cost"])
        self.assertGreater(a["first_year_net_fee_savings"],0)

    def test_threshold_and_turnover(self):
        p = scenario()
        p["max_turnover"] = .01
        r = run_plan(p)
        self.assertEqual(r["turnover"], 0)
        self.assertTrue(all(a["action"]=="HOLD" for a in r["actions"]))

    def test_infeasible_cap(self):
        p = scenario()
        p["positions"][0]["elss"] = True
        p["positions"][0]["max_weight"] = .6
        r = run_plan(p)
        self.assertEqual(r["status"],"no_feasible_trades")
        self.assertEqual(r["actions"],[])

    def test_indefinite_covariance_rejected(self):
        p = scenario()
        p["covariance"]=[[.1,.4],[.4,.1]]
        with self.assertRaisesRegex(ValueError,"covariance"):
            run_plan(p)
