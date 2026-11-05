#!/bin/bash
# DB2 community image 在建立好 HISDB 之後，會執行 /var/custom 底下的腳本（以 root 身分）
# 只在第一次初始化時建表與匯入資料（以 /database 上的標記檔判斷）
set -u
MARK=/database/.hisdb_seeded
if [ -f "$MARK" ]; then
  echo "[hisdb] already seeded, skip"
  exit 0
fi

echo "[hisdb] tuning database config"
su - db2inst1 -c "db2 update db cfg for HISDB using LOGFILSIZ 16384 LOGPRIMARY 10 LOGSECOND 40 LOCKTIMEOUT 20"
# 監控需要的開關（Instana Db2 sensor 透過 MON_GET_* 取得指標）
su - db2inst1 -c "db2 update db cfg for HISDB using MON_REQ_METRICS BASE MON_ACT_METRICS BASE MON_OBJ_METRICS EXTENDED MON_LOCKWAIT HIST_AND_VALUES MON_LW_THRESH 2000000 MON_DEADLOCK HIST_AND_VALUES MON_LOCKTIMEOUT HIST_AND_VALUES"
su - db2inst1 -c "db2 terminate; db2 deactivate db HISDB; db2 activate db HISDB"

echo "[hisdb] creating schema and seed data"
su - db2inst1 -c "db2 -tvf /hisdb-sql/hisdb.sql" > /tmp/hisdb-seed.log 2>&1
rc=$?
tail -40 /tmp/hisdb-seed.log
# db2 CLP: 1=無資料列, 2=警告（遞迴 CTE 會出 SQL0347W），4/8 才是錯誤
if [ $rc -ge 4 ]; then
  echo "[hisdb] seed finished with errors (rc=$rc), see /tmp/hisdb-seed.log"
  exit 1
fi
touch "$MARK"
echo "[hisdb] done"
