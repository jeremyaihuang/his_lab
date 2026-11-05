#!/usr/bin/env bash
# 在本機 build 所有 image：hospital/was-his、hospital/was-pharmacy、hospital/ihs
# 用法：scripts/build-images.sh [his|pharmacy|ihs ...]（不帶參數 = 全部）
set -euo pipefail
cd "$(dirname "$0")/.."
source hospital.env
targets=${*:-"his pharmacy ihs"}

if [[ " $targets " == *" his "* || " $targets " == *" pharmacy "* ]]; then
  (cd apps && mvn -q -B package)
fi
for t in $targets; do
  case $t in
    his)      docker build -f was/Dockerfile -t hospital/was-his:$IMAGE_TAG \
                --build-arg APP=his --build-arg CONTEXT=/his --build-arg WEB_THREADS=20 --build-arg POOL_MAX=15 --build-arg HEAP_MAX=768 \
                --build-arg DB_PASSWORD="$DB2_PASSWORD" --build-arg ADMIN_PASSWORD="$WAS_ADMIN_PASSWORD" . ;;
    pharmacy) docker build -f was/Dockerfile -t hospital/was-pharmacy:$IMAGE_TAG \
                --build-arg APP=pharmacy --build-arg CONTEXT=/pharmacy --build-arg WEB_THREADS=30 --build-arg POOL_MAX=15 --build-arg HEAP_MAX=768 \
                --build-arg DB_PASSWORD="$DB2_PASSWORD" --build-arg ADMIN_PASSWORD="$WAS_ADMIN_PASSWORD" . ;;
    ihs)      docker build -f ihs/Dockerfile -t hospital/ihs:$IMAGE_TAG . ;;
    *) echo "unknown target $t"; exit 1 ;;
  esac
done
docker images 'hospital/*'
