#!/usr/bin/env bash
# 透過 SSH 把本機 build 的 image 匯入 worker 的 containerd（不經過 registry）
# 用法：SSHPASS='<root 密碼>' scripts/import-images-ssh.sh [worker IP ...]（預設 10.107.85.92 10.107.85.93）
#       也可以不設 SSHPASS，改用 SSH 金鑰登入
set -euo pipefail
cd "$(dirname "$0")/.."
source hospital.env
nodes=${*:-"10.107.85.92 10.107.85.93"}
images="hospital/was-his:$IMAGE_TAG hospital/was-pharmacy:$IMAGE_TAG hospital/ihs:$IMAGE_TAG"
ssh_cmd=(ssh -o StrictHostKeyChecking=accept-new)
[ -n "${SSHPASS:-}" ] && ssh_cmd=(sshpass -e ssh -o StrictHostKeyChecking=accept-new -o PubkeyAuthentication=no)
for h in $nodes; do
  echo "== $h"
  docker save $images | "${ssh_cmd[@]}" root@"$h" 'ctr -n k8s.io images import - >/dev/null && ctr -n k8s.io images ls -q | grep hospital/'
done
