#!/usr/bin/env bash
# 部署仁心醫院 HIS 到 K8s（namespace hospital）
# 前置：scripts/build-images.sh 與 scripts/import-images.sh 已完成
set -euo pipefail
cd "$(dirname "$0")/.."
source hospital.env
kubectl create ns hospital --dry-run=client -o yaml | kubectl apply -f -
kubectl -n hospital create secret generic hospital-db2 --from-literal=DB2INST1_PASSWORD="$DB2_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n hospital create configmap hospital-db2-init --from-file=db2/init-hisdb.sh --from-file=db2/hisdb.sql \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n hospital create configmap hospital-loadgen --from-file=loadgen/loadgen.py \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f k8s/10-db2.yaml
echo "等待 DB2 就緒（第一次初始化約 10 分鐘）..."
kubectl -n hospital rollout status sts/hospital-db2 --timeout=1200s
kubectl apply -f k8s/20-apps.yaml
kubectl -n hospital rollout status deploy/hospital-was-pharmacy --timeout=600s
kubectl -n hospital rollout status deploy/hospital-was-his --timeout=600s
kubectl -n hospital rollout status deploy/hospital-ihs --timeout=300s
kubectl apply -f k8s/30-loadgen.yaml
scripts/instana-agent-config.sh
kubectl -n hospital get pod -o wide
echo "入口：http://<任一節點 IP>:30480/"
