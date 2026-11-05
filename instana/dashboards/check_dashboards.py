#!/usr/bin/env python3
"""檢查 Dashboard 中每條指標在 Instana 是否查得到資料（用與 widget 相同的條件呼叫 Instana API）。

用法：INSTANA_BASE_URL=... INSTANA_API_TOKEN=... python3 check_dashboards.py [json ...]
"""
import json
import os
import pathlib
import ssl
import sys
import urllib.request

BASE = os.environ["INSTANA_BASE_URL"].rstrip("/")
TOKEN = os.environ["INSTANA_API_TOKEN"]
HERE = pathlib.Path(__file__).resolve().parent
WINDOW = 15 * 60 * 1000
CTX = ssl.create_default_context(cafile=os.environ.get("INSTANA_CA_BUNDLE") or None)


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"apiToken {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return {"__error": f"HTTP {e.code} {e.read()[:200].decode(errors='replace')}"}


def check_infra(m):
    body = {"timeFrame": {"windowSize": WINDOW}, "type": m["type"],
            "metrics": [{"metric": m["metric"], "aggregation": m["aggregation"], "granularity": 60}],
            "tagFilterExpression": m["tagFilterExpression"], "pagination": {"retrievalSize": 20}}
    r = post("/api/infrastructure-monitoring/analyze/entities", body)
    if "__error" in r:
        return False, r["__error"]
    items = r.get("items", [])
    vals = [v for it in items for series in it.get("metrics", {}).values() for _, v in series]
    return bool(vals), f"{len(items)} entities, {len(vals)} points"


def check_app(m):
    agg = m["aggregation"]
    filt = {"type": "EXPRESSION", "logicalOperator": "AND", "elements": [
        {"type": "TAG_FILTER", "name": "application.id", "operator": "EQUALS", "entity": "DESTINATION",
         "value": m["applicationId"]},
        m["tagFilterExpression"]]}
    body = {"timeFrame": {"windowSize": WINDOW}, "group": {"groupbyTag": "service.name", "groupbyTagEntity": "DESTINATION"},
            "metrics": [{"metric": m["metric"], "aggregation": agg}], "tagFilterExpression": filt,
            "includeInternal": False, "includeSynthetic": False}
    r = post("/api/application-monitoring/analyze/call-groups", body)
    if "__error" in r:
        return False, r["__error"]
    items = r.get("items", [])
    vals = [v for it in items for series in it.get("metrics", {}).values() for _, v in series if v is not None]
    return bool(items), f"{len(items)} groups, values={vals[:3]}"


def metrics_of(widget):
    c = widget["config"]
    if widget["type"] == "chart":
        return c["y1"]["metrics"] + c["y2"]["metrics"]
    if widget["type"] == "bigNumber":
        return [c["metricConfiguration"]]
    return []


files = sys.argv[1:] or sorted(str(p) for p in HERE.glob("[0-9]-*.json") if ".created" not in p.name)
bad = 0
for f in files:
    d = json.load(open(f))
    print(f"\n=== {d['title']}")
    for w in d["widgets"]:
        for m in metrics_of(w):
            ok, info = (check_app if m["source"] == "APPLICATION" else check_infra)(m)
            bad += not ok
            print(f"  {'OK ' if ok else 'NO '} [{w['type']}] {w['title']} / {m['label']} ({m['metric']} {m['aggregation']}) → {info}")
print(f"\n共 {bad} 條指標查不到資料")
