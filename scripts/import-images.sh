#!/usr/bin/env bash
# 把本機 build 的 hospital/* image 匯入每台 worker 的 containerd（k8s.io namespace），不經過 registry。
# 作法：在 hospital namespace 起一個 privileged DaemonSet（掛載節點根目錄），
#       docker save 串流給節點上的 `ctr -n k8s.io images import -`，完成後刪除 DaemonSet。
# 用法：scripts/import-images.sh [image ...]（預設 hospital/was-his hospital/was-pharmacy hospital/ihs）
set -euo pipefail
cd "$(dirname "$0")/.."
source hospital.env
images=${*:-"hospital/was-his:$IMAGE_TAG hospital/was-pharmacy:$IMAGE_TAG hospital/ihs:$IMAGE_TAG"}

kubectl apply -f k8s/node-importer.yaml
trap 'kubectl -n hospital delete ds hospital-image-importer --wait=false >/dev/null 2>&1 || true' EXIT
kubectl -n hospital rollout status ds/hospital-image-importer --timeout=180s

for pod in $(kubectl -n hospital get pod -l app=hospital-image-importer -o jsonpath='{.items[*].metadata.name}'); do
  node=$(kubectl -n hospital get pod "$pod" -o jsonpath='{.spec.nodeName}')
  echo "== $node：匯入 $images"
  docker save $images | kubectl -n hospital exec -i "$pod" -- chroot /host ctr -n k8s.io images import -
  kubectl -n hospital exec "$pod" -- chroot /host ctr -n k8s.io images ls -q | grep hospital/ || true
done
echo "完成，DaemonSet 會自動刪除"
