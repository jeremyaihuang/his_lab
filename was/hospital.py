# 仁心醫院 HIS — tWAS 設定（docker build 時由 /work/configure.sh 以 wsadmin 執行，server 為啟動狀態）
# 參數由環境變數帶入：HOSP_APP / HOSP_CONTEXT / HOSP_WEB_THREADS / HOSP_POOL_MAX / HOSP_HEAP_MAX / HOSP_DB_PASSWORD
import java.lang.System as S

app = S.getenv('HOSP_APP')
ctx = S.getenv('HOSP_CONTEXT')
threads = S.getenv('HOSP_WEB_THREADS')
poolMax = S.getenv('HOSP_POOL_MAX')
heapMax = S.getenv('HOSP_HEAP_MAX')
dbPassword = S.getenv('HOSP_DB_PASSWORD')
node = 'DefaultNode01'
srv = 'server1'
server = AdminConfig.getid('/Node:%s/Server:%s/' % (node, srv))
print '### configuring %s (context %s)' % (app, ctx)

# ---- DB2 JDBC Provider / J2C 認證別名 / DataSource jdbc/HISDB ----
prov = AdminTask.createJDBCProvider('[-scope Node=%s,Server=%s -databaseType DB2 '
    '-providerType "DB2 Universal JDBC Driver Provider" -implementationType "Connection pool data source" '
    '-name "DB2 Universal JDBC Driver Provider" -classpath [/work/lib/db2jcc4.jar] ]' % (node, srv))
AdminTask.createAuthDataEntry('[-alias hisdb -user db2inst1 -password %s -description "HISDB on DB2"]' % dbPassword)
ds = AdminTask.createDatasource(prov, '[-name HISDB -jndiName jdbc/HISDB '
    '-dataStoreHelperClassName com.ibm.websphere.rsadapter.DB2UniversalDataStoreHelper '
    '-containerManagedPersistence false -componentManagedAuthenticationAlias %s/hisdb '
    '-configureResourceProperties [[databaseName java.lang.String HISDB] [driverType java.lang.Integer 4] '
    '[serverName java.lang.String hospital-db2] [portNumber java.lang.Integer 50000]]]' % node)
cp = AdminConfig.showAttribute(ds, 'connectionPool')
# Connection Pool 刻意設小，conn-leak / db-lock 情境才看得出耗盡
AdminConfig.modify(cp, [['maxConnections', poolMax], ['minConnections', '2'], ['connectionTimeout', '10'],
                        ['reapTime', '60'], ['unusedTimeout', '300'], ['agedTimeout', '0']])
print '### datasource jdbc/HISDB pool max=%s' % poolMax

# ---- WebContainer Thread Pool ----
for tp in AdminConfig.list('ThreadPool', server).splitlines():
    if AdminConfig.showAttribute(tp, 'name') == 'WebContainer':
        AdminConfig.modify(tp, [['minimumSize', '5'], ['maximumSize', threads]])
        print '### WebContainer thread pool max=%s' % threads

# ---- Hung Thread 偵測（預設 600 秒太久，改 20 秒，示範時才看得到） ----
for name, value in [['com.ibm.websphere.threadmonitor.interval', '10'],
                    ['com.ibm.websphere.threadmonitor.threshold', '20'],
                    ['com.ibm.websphere.threadmonitor.false.alarm.threshold', '100']]:
    try:
        AdminConfig.create('Property', server, [['name', name], ['value', value]], 'properties')
        print '### server custom property %s=%s' % (name, value)
    except:
        # 退而求其次：設成 JVM system property
        jvm0 = AdminConfig.list('JavaVirtualMachine', server)
        AdminConfig.create('Property', jvm0, [['name', name], ['value', value]], 'systemProperties')
        print '### JVM system property %s=%s' % (name, value)

# ---- JVM：Heap 上限，OOM 時不產生大型 heapdump ----
jvm = AdminConfig.list('JavaVirtualMachine', server)
AdminConfig.modify(jvm, [['initialHeapSize', '256'], ['maximumHeapSize', heapMax],
    ['genericJvmArguments', '-Xdump:heap:none -Xdump:system:none -Dfile.encoding=UTF-8 -Dclient.encoding.override=UTF-8']])

# ---- PMI：extended（Instana WebSphere sensor 從 PMI 取 Thread Pool / JDBC Pool / Session 指標） ----
pmi = AdminConfig.list('PMIService', server)
AdminConfig.modify(pmi, [['enable', 'true'], ['statisticSet', 'extended']])

# ---- default_host 接受任何 Host/Port（IHS Plugin 會轉送原始 Host header，例如 NodePort） ----
vh = AdminConfig.getid('/VirtualHost:default_host/')
AdminConfig.create('HostAlias', vh, [['hostname', '*'], ['port', '*']])

AdminConfig.save()

# ---- 安裝應用程式 ----
AdminApp.install('/work/app/%s.war' % app, '[-appname %s -contextroot %s -MapWebModToVH [[.* .* default_host]] -usedefaultbindings]' % (app, ctx))
AdminConfig.save()
print '### installed %s at %s' % (app, ctx)

# 給 Dockerfile 檢查設定有沒有完整跑完
f = open('/work/hospital-configured', 'w'); f.write(app); f.close()
