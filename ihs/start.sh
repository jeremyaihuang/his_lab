#!/bin/bash
# 前景執行 IHS（PID 1），環境變數取自 IHS 的 envvars（LD_LIBRARY_PATH 等）
cd /opt/IHS
[ -f bin/envvars ] && . bin/envvars
bin/httpd -d /opt/IHS -f /opt/IHS/conf/httpd.conf -t || exit 1
exec bin/httpd -d /opt/IHS -f /opt/IHS/conf/httpd.conf -DFOREGROUND
