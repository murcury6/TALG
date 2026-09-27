"""Run synthetic engineering cases against the installed small model, not a benchmark."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from talg_py.news_connection import ConnectionModel
from talg_py.news_relatedness import RelatednessModel
from talg_py.news_relevance_ai import filter_articles
from talg_py.news_tensor import atomic_json, digest


def main():
    model = json.loads((ROOT / "research/news-average/relatedness.json").read_text())
    weights = ConnectionModel(json.loads((ROOT / "research/news-average/connection-model.json").read_text()))
    router = RelatednessModel(model)
    cases = json.loads((ROOT / "research/news-average/relevance-smoke-cases.json").read_text())
    rows = []
    for case in cases:
        row = dict(case, id=digest(case["case"]), content_hash=digest(case["text"]))
        row["relations"] = router.route(row, []).get("AAPL", [])
        rows.append(row)
    folder = ROOT / "work/symbol-news-tensor/relevance-smoke"
    folder.mkdir(parents=True, exist_ok=True)
    _, audit, report = filter_articles({"AAPL": rows}, model, ROOT, folder / "scores.sqlite")
    results = []
    for row, decision in zip(rows, audit["AAPL"], strict=True):
        connection = weights.predict("AAPL", dict(row, relevance_ai=decision)) if decision["include"] else None
        correct = None if row["expected_include"] is None else row["expected_include"] == decision["include"]
        results.append(dict(row, decision=decision, connection_weight=connection, expected_matches=correct))
        print(row["case"], decision["state"], round(decision["score"], 3), "expected_matches", correct)
    atomic_json(folder / "results.json", {"scope": "Synthetic engineering checks, not independent accuracy evaluation", "report": report, "cases": results})
    atomic_json(folder / "model.json", model)
    atomic_json(folder / "connection-model.json", weights.config)
    if any(r["expected_matches"] is False for r in results):
        raise SystemExit("Synthetic relevance regression failed; inspect saved cases")


if __name__ == "__main__":
    main()
