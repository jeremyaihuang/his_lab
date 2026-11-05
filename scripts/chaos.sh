#!/usr/bin/env bash
# 仁心醫院 HIS — 錯誤情境控制
#
#   scripts/chaos.sh list                     列出所有情境與目前狀態
#   scripts/chaos.sh on  <情境> [值]          開啟情境
#   scripts/chaos.sh off <情境>               關閉情境
#   scripts/chaos.sh reset                    關閉全部情境、恢復所有元件
#
# 應用程式情境（在 WAS 內切換；共用情境可加 pharmacy: 前綴指定藥局 WAS，例如 pharmacy:mem-leak）
#   slow-sql          病歷查詢全表掃描（DROP 索引）                        [HIS]
#   npe-bug           無健保卡病患掛號 NullPointerException                 [HIS]
#   register-error [%] 掛號失敗率（預設 30%）                                [HIS]
#   mem-leak [KB]     每個請求洩漏 N KB（預設 512）→ Heap 成長、OOM          [HIS，可 pharmacy:]
#   conn-leak         JDBC 連線洩漏 → Connection Pool 耗盡                  [HIS，可 pharmacy:]
#   cpu-burn [ms]     每個請求多耗 N 毫秒 CPU（預設 300）                    [HIS，可 pharmacy:]
#   db-lock [秒]      背景批次鎖住常用藥品 N 秒（預設 40）→ Lock Wait/Timeout [Pharmacy]
#   deadlock          開處方時更新順序不一致 → DB2 Deadlock                 [Pharmacy]
#   slow-downstream [秒] 健保署閘道延遲 N 秒（預設 25）→ Thread Pool 滿、Hung Thread [Pharmacy]
#   nhi-down          健保署閘道回 HTTP 503 → 掛號失敗                       [Pharmacy]
# 平台情境（kubectl）
#   pharmacy-down     藥局 WAS 縮成 0 個 Pod → IHS Plugin / 門診呼叫失敗
#   db2-down          db2stop force → 所有 JDBC 連線失敗
#   was-crash         砍掉門診 WAS 的 Java 程序 → Pod 重啟
set -euo pipefail
NS=hospital
HIS_SCENARIOS="slow-sql npe-bug register-error mem-leak conn-leak cpu-burn"
PHARMACY_SCENARIOS="db-lock deadlock slow-downstream nhi-down"
PLATFORM_SCENARIOS="pharmacy-down db2-down was-crash"

pod_of() { kubectl -n $NS get pod -l app="$1" --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null; }

# 在 WAS Pod 內呼叫自己的 /api/chaos
was_api() { # app method path
  local app=$1 method=$2 path=$3 pod
  pod=$(pod_of hospital-was-$app)
  [ -z "$pod" ] && { echo "hospital-was-$app 沒有執行中的 Pod" >&2; return 1; }
  kubectl -n $NS exec "$pod" -- curl -s -m 60 -X "$method" "http://localhost:9080/$app/api/chaos$path"
}

# 送自訂事件到 Instana Agent（顯示在 Instana 時間軸，方便對照）
instana_event() { # title text severity(-1 info, 5 warning, 10 critical)
  local pod hostip
  pod=$(pod_of hospital-was-his) || return 0
  [ -z "$pod" ] && return 0
  hostip=$(kubectl -n $NS get pod "$pod" -o jsonpath='{.status.hostIP}')
  kubectl -n $NS exec "$pod" -- curl -s -m 5 -X POST "http://$hostip:42699/com.instana.plugin.generic.event" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"$1\",\"text\":\"$2\",\"severity\":$3,\"duration\":60000}" >/dev/null 2>&1 || true
}

target_app() { # scenario → his|pharmacy|platform
  local s=$1
  case $s in pharmacy:*) echo pharmacy; return ;; his:*) echo his; return ;; esac
  for x in $HIS_SCENARIOS; do [ "$x" = "$s" ] && { echo his; return; }; done
  for x in $PHARMACY_SCENARIOS; do [ "$x" = "$s" ] && { echo pharmacy; return; }; done
  for x in $PLATFORM_SCENARIOS; do [ "$x" = "$s" ] && { echo platform; return; }; done
  echo unknown
}

platform() { # action on|off name
  case "$2" in
    pharmacy-down)
      if [ "$1" = on ]; then kubectl -n $NS scale deploy/hospital-was-pharmacy --replicas=0
      else kubectl -n $NS scale deploy/hospital-was-pharmacy --replicas=1; fi ;;
    db2-down)
      if [ "$1" = on ]; then kubectl -n $NS exec hospital-db2-0 -- su - db2inst1 -c "db2stop force"
      else kubectl -n $NS exec hospital-db2-0 -- su - db2inst1 -c "db2start; db2 activate db HISDB"; fi ;;
    was-crash)
      [ "$1" = on ] && kubectl -n $NS exec "$(pod_of hospital-was-his)" -- sh -c 'kill -9 $(pgrep -f com.ibm.ws.runtime.WsServer)' || true
      echo "門診 WAS 的 Java 程序已終止，Kubernetes 會自動重啟容器" ;;
  esac
}

cmd=${1:-list}
case $cmd in
  list)
    for app in his pharmacy; do echo "== WAS-$app"; was_api $app GET "" | python3 -I "$(dirname "$0")/chaos_list.py" || true; done
    echo "== 平台情境：$PLATFORM_SCENARIOS"
    kubectl -n $NS get deploy hospital-was-pharmacy -o jsonpath='  pharmacy replicas={.spec.replicas}{"\n"}' ;;
  on|off)
    name=${2:?請指定情境名稱，scripts/chaos.sh list 可看全部}
    value=${3:-}
    app=$(target_app "$name"); base=${name#*:}
    case $app in
      his|pharmacy)
        if [ "$cmd" = on ]; then was_api $app PUT "/$base?value=$value" >/dev/null; else was_api $app DELETE "/$base" >/dev/null; fi
        echo "WAS-$app 情境 $base → $cmd" ;;
      platform) platform "$cmd" "$base" ;;
      *) echo "未知的情境：$name"; exit 1 ;;
    esac
    if [ "$cmd" = on ]; then instana_event "[CHAOS] $base ON" "錯誤情境 $base 已開啟（值：${value:-預設}）" 5
    else instana_event "[CHAOS] $base OFF" "錯誤情境 $base 已關閉" -1; fi ;;
  reset)
    kubectl -n $NS scale deploy/hospital-was-pharmacy --replicas=1 >/dev/null
    kubectl -n $NS exec hospital-db2-0 -- su - db2inst1 -c "db2start >/dev/null 2>&1; db2 activate db HISDB >/dev/null 2>&1" || true
    kubectl -n $NS rollout status deploy/hospital-was-pharmacy --timeout=300s >/dev/null || true
    for app in his pharmacy; do was_api $app DELETE "" >/dev/null && echo "WAS-$app 情境已全部關閉"; done
    instana_event "[CHAOS] reset" "所有錯誤情境已關閉" -1 ;;
  *) sed -n '2,30p' "$0"; exit 1 ;;
esac
