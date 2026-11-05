#!/usr/bin/env bash
# 把仁心醫院需要的設定加進 InstanaAgent CR（spec.agent.configuration_yaml），Operator 會熱載入
#  1. 在既有的 com.instana.tracing.extra-http-headers 清單加入 X-Patient-Id（沒有該區塊就新增）
#  2. 附加 instana/configuration-hospital.yaml（Db2 / WebSphere / HTTPd）
# 只會加一次（以標記行判斷）。用法：scripts/instana-agent-config.sh [agent-namespace] [agent-name]
set -euo pipefail
cd "$(dirname "$0")/.."
AGENT_NS=${1:-instana-agent}
AGENT_NAME=${2:-instana-agent}
MARK='# --- hospital HIS demo (scripts/instana-agent-config.sh) ---'

current=$(kubectl -n "$AGENT_NS" get instanaagent "$AGENT_NAME" -o jsonpath='{.spec.agent.configuration_yaml}')
if grep -qF -- "$MARK" <<<"$current"; then
  echo "InstanaAgent ${AGENT_NS}/${AGENT_NAME} 已包含仁心醫院設定，不重複加入"; exit 0
fi
kubectl -n "$AGENT_NS" get instanaagent "$AGENT_NAME" -o yaml > "instana/instanaagent-backup-$(date +%Y%m%d%H%M%S).yaml"

CURRENT="$current" ADDITION="$(set -a; source hospital.env; envsubst '${DB2_PASSWORD} ${WAS_ADMIN_USER} ${WAS_ADMIN_PASSWORD}' < instana/configuration-hospital.yaml)" MARK="$MARK" python3 -I scripts/merge_agent_config.py | \
  kubectl -n "$AGENT_NS" patch instanaagent "$AGENT_NAME" --type merge --patch-file /dev/stdin
echo "已更新 InstanaAgent ${AGENT_NS}/${AGENT_NAME}，Agent 約 1 分鐘內載入新設定（備份在 instana/instanaagent-backup-*.yaml）"
