#!/usr/bin/env bash
# 重新產生 Dashboard 定義並更新到既有的 Dashboard（ID 取自 instana/dashboards/*.created.json，網址不變）
# 用法：INSTANA_BASE_URL=https://... INSTANA_API_TOKEN=... scripts/update-dashboards.sh <application-perspective-id>
set -euo pipefail
cd "$(dirname "$0")/../instana/dashboards"
: "${INSTANA_BASE_URL:?請設定 INSTANA_BASE_URL}" "${INSTANA_API_TOKEN:?請設定 INSTANA_API_TOKEN}"
python3 -I build_dashboards.py "${1:?請指定 Application Perspective ID}"
for f in 1-executive 2-ap 3-middleware 4-dba 5-jvm; do
  id=$(python3 -c "import json;print(json.load(open('$f.created.json'))['id'])")
  python3 -c "import json,sys;d=json.load(open('$f.json'));d['id']='$id';print(json.dumps(d,ensure_ascii=False))" | \
    curl -sS -X PUT "$INSTANA_BASE_URL/api/custom-dashboard/$id" -H "Authorization: apiToken $INSTANA_API_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- -o /dev/null -w "$f ($id) HTTP %{http_code}\n"
done
