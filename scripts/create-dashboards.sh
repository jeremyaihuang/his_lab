#!/usr/bin/env bash
# 產生並建立 4 個依角色劃分的 Instana Custom Dashboard
# 用法：INSTANA_BASE_URL=https://... INSTANA_API_TOKEN=... scripts/create-dashboards.sh <application-perspective-id>
set -euo pipefail
cd "$(dirname "$0")/../instana/dashboards"
: "${INSTANA_BASE_URL:?請設定 INSTANA_BASE_URL}" "${INSTANA_API_TOKEN:?請設定 INSTANA_API_TOKEN}"
python3 -I build_dashboards.py "${1:?請指定 Application Perspective ID}"
for f in 1-executive 2-ap 3-middleware 4-dba; do
  curl -sS -X POST "$INSTANA_BASE_URL/api/custom-dashboard" -H "Authorization: apiToken $INSTANA_API_TOKEN" \
    -H 'Content-Type: application/json' --data-binary @$f.json -o $f.created.json -w "$f HTTP %{http_code}  "
  python3 -c "import json;print(json.load(open('$f.created.json')).get('id'))"
done
