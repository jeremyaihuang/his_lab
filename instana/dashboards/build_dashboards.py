#!/usr/bin/env python3
"""產生仁心醫院 HIS 的 Instana Custom Dashboard 定義（依角色劃分）。

輸出：instana/dashboards/<key>.json（Instana Custom Dashboard API 的 CustomDashboard 物件）
建立：透過 Instana MCP manage_custom_dashboards create，或
      curl -X POST $INSTANA_BASE_URL/api/custom-dashboard -H "Authorization: apiToken $TOKEN" -d @<key>.json

用法：python3 build_dashboards.py <application-perspective-id>
"""
import json
import pathlib
import sys

APP_ID = sys.argv[1] if len(sys.argv) > 1 else "y0q3DYIfRxWbVRD-aXmLEQ"
OUT = pathlib.Path(__file__).resolve().parent
ALL = {"type": "EXPRESSION", "logicalOperator": "AND", "elements": []}
NO_THRESHOLD = {"critical": "", "warning": "", "thresholdEnabled": False, "operator": ">="}

# 主要業務交易（JAX-RS endpoint 名稱）
EP_REGISTER = "POST /appointments"
EP_VISIT = "POST /visits/{apptId}/complete"
EP_RECORDS = "GET /patients/{id}/records"
EP_PATIENT = "GET /patients/{id}"
EP_RX = "POST /prescriptions"
EP_NHI = "GET /nhi/verify"
EP_LAB = "GET /lab/patients/{id}"
EP_DRUG = "GET /drugs/{id}"
EP_DISPENSE = "POST /prescriptions/{rxId}/dispense"
EP_SEARCH = "GET /patients/search"


def tf(name, value, entity="DESTINATION", operator="EQUALS"):
    return {"type": "TAG_FILTER", "name": name, "operator": operator, "entity": entity, "value": value}


def and_(*elements):
    return {"type": "EXPRESSION", "logicalOperator": "AND", "elements": list(elements)}


NOT_HEALTH = tf("endpoint.name", "GET /health", operator="NOT_EQUAL")


def http_status(lo, hi):
    """HTTP 狀態碼區間（call.http.status 為數值 tag）"""
    return and_(NOT_HEALTH,
                tf("call.http.status", lo, entity="NOT_APPLICABLE", operator="GREATER_OR_EQUAL_THAN"),
                tf("call.http.status", hi, entity="NOT_APPLICABLE", operator="LESS_OR_EQUAL_THAN"))


def app(metric, aggregation, label, filt=None, color=""):
    """Application Perspective 的呼叫指標"""
    return {
        "type": "application", "source": "APPLICATION",
        "applicationId": APP_ID, "applicationBoundaryScope": "INBOUND",
        "metric": metric, "aggregation": aggregation, "label": label, "color": color,
        "tagFilterExpression": filt or and_(NOT_HEALTH),
        "grouping": [], "includeInternal": False, "includeSynthetic": False,
        "timeShift": 0, "lastValue": False, "threshold": NO_THRESHOLD, "widgetType": "chart",
    }


def infra(entity_type, metric, aggregation, label, deployment=None, color=""):
    """Infrastructure 指標；deployment 指定 K8s Deployment / StatefulSet 名稱"""
    filt = ALL
    if deployment:
        tag = "kubernetes.statefulset.name" if deployment == "hospital-db2" else "kubernetes.deployment.name"
        filt = tf(tag, deployment, entity="NOT_APPLICABLE")
    return {
        "type": entity_type, "source": "INFRASTRUCTURE_METRICS",
        "metric": metric, "aggregation": aggregation, "crossSeriesAggregation": aggregation,
        "label": label, "color": color, "tagFilterExpression": filt, "regex": False,
        "timeShift": 0, "lastValue": False, "threshold": NO_THRESHOLD, "widgetType": "chart",
    }


class Layout:
    """12 欄格線，依序排版"""

    def __init__(self):
        self.widgets, self.x, self.y, self.row_h, self.n = [], 0, 0, 0, 0

    def add(self, wtype, title, config, width, height):
        if self.x + width > 12:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        self.n += 1
        self.widgets.append({"id": f"w{self.n:02d}", "title": title, "type": wtype,
                             "x": self.x, "y": self.y, "width": width, "height": height, "config": config})
        self.x += width
        self.row_h = max(self.row_h, height)

    def markdown(self, title, text, width=12, height=6):
        # Instana 的 Markdown widget：config 直接是 Markdown 字串（不是物件）
        self.add("markdown", title, text, width, height)

    def chart(self, title, metrics, width=6, height=13, formatter="number.detailed", y2=None, y2_formatter="number.detailed"):
        cfg = {"type": "TIME_SERIES",
               "y1": {"formatter": formatter, "renderer": "line", "metrics": metrics},
               "y2": {"formatter": y2_formatter, "renderer": "line", "metrics": y2 or []}}
        self.add("chart", title, cfg, width, height)

    def big(self, title, metric, width=3, height=7, formatter="number.detailed"):
        m = dict(metric, widgetType="bigNumber")
        self.add("bigNumber", title, {"metricConfiguration": m, "formatter": formatter}, width, height)


def dashboard(title, layout):
    return {"title": title,
            "accessRules": [{"accessType": "READ_WRITE", "relationType": "GLOBAL", "relatedId": None}],
            "widgets": layout.widgets}


def ep(name, service=None):
    els = [tf("endpoint.name", name)]
    if service:
        els.append(tf("service.name", service))
    return and_(*els)


def svc(name):
    return and_(tf("service.name", name), NOT_HEALTH)


# ---------------------------------------------------------------- 1. 主管 / 營運總覽
def executive():
    L = Layout()
    L.markdown("給誰看、怎麼看",
               "### 🏥 仁心醫院 HIS｜主管營運總覽\n"
               "**對象**：院方主管、資訊室主管　**用途**：每天看一眼就知道「病人有沒有被影響」。\n\n"
               "只看三件事：**服務量**（今天掛號、看診有沒有照常進行）、**錯誤率**（病人有沒有遇到失敗）、"
               "**回應時間**（櫃台與醫師有沒有在等系統）。數字轉紅時，點進 AP / 中介軟體 / DBA 儀表板找原因。", height=6)
    L.big("總交易量（呼叫數）", app("calls", "SUM", "總呼叫數"))
    L.big("錯誤率", app("errors", "MEAN", "錯誤率"), formatter="percentage.detailed")
    L.big("回應時間 P95", app("latency", "P95", "P95 延遲"), formatter="millis.detailed")
    L.big("掛號件數", app("calls", "SUM", "掛號", ep(EP_REGISTER)))
    L.chart("業務交易量（每秒）", [
        app("calls", "PER_SECOND", "掛號", ep(EP_REGISTER)),
        app("calls", "PER_SECOND", "看診完成", ep(EP_VISIT)),
        app("calls", "PER_SECOND", "查病歷", ep(EP_RECORDS)),
        app("calls", "PER_SECOND", "開處方", ep(EP_RX)),
        app("calls", "PER_SECOND", "藥局領藥", ep(EP_DISPENSE)),
    ])
    L.chart("錯誤率與 HTTP 5xx", [app("errors", "MEAN", "錯誤率")], formatter="percentage.detailed",
            y2=[app("calls", "SUM", "HTTP 5xx 次數", http_status(500, 599))])
    L.chart("關鍵交易回應時間 P95", [
        app("latency", "P95", "掛號", ep(EP_REGISTER)),
        app("latency", "P95", "看診完成", ep(EP_VISIT)),
        app("latency", "P95", "查病歷", ep(EP_RECORDS)),
    ], formatter="millis.detailed")
    L.chart("外部依賴：健保卡驗證（健保署閘道）", [app("latency", "P95", "健保驗證 P95", ep(EP_NHI))],
            formatter="millis.detailed", y2=[app("erroneousCalls", "SUM", "健保驗證失敗", ep(EP_NHI))])
    L.markdown("顧問建議：主管 KPI 與門檻",
               "| KPI | 建議目標（SLO） | 轉黃 | 轉紅 |\n|---|---|---|---|\n"
               "| 掛號成功率 | ≥ 99.5% | < 99.5% | < 99% |\n"
               "| 掛號 P95 回應時間 | ≤ 1 秒 | > 1 秒 | > 3 秒 |\n"
               "| 看診完成（病歷 + 處方）P95 | ≤ 2 秒 | > 2 秒 | > 5 秒 |\n"
               "| 整體錯誤率 | ≤ 0.5% | > 0.5% | > 2% |\n"
               "| 健保驗證可用率 | ≥ 99% | 連續 5 分鐘失敗 | 連續 15 分鐘失敗 |\n\n"
               "建議把「掛號成功率」與「看診完成 P95」設成 Instana SLO，每週例會檢視 Error Budget。", height=10)
    return dashboard("仁心醫院 HIS｜1. 主管營運總覽", L)


# ---------------------------------------------------------------- 2. AP 開發 / 應用維運
def ap():
    L = Layout()
    L.markdown("給誰看、怎麼看",
               "### 🧑‍💻 仁心醫院 HIS｜AP 應用維運\n"
               "**對象**：AP 開發、應用維運　**用途**：找出「哪一支 API 慢 / 錯」以及「是不是程式或 JVM 的問題」。\n\n"
               "看法：先看 Endpoint P95 找出變慢的交易 → 看錯誤呼叫找出出錯的服務 → 看 DB 呼叫判斷是不是 SQL 拖慢 → "
               "最後看 JVM Heap / GC 判斷是否記憶體洩漏。點圖表上的時間點可直接跳到 Trace。", height=6)
    L.chart("門診 WAS：Endpoint 回應時間 P95", [
        app("latency", "P95", "掛號", ep(EP_REGISTER)),
        app("latency", "P95", "看診完成", ep(EP_VISIT)),
        app("latency", "P95", "查病歷", ep(EP_RECORDS)),
        app("latency", "P95", "查病患", ep(EP_PATIENT)),
        app("latency", "P95", "病患搜尋", ep(EP_SEARCH)),
    ], formatter="millis.detailed")
    L.chart("藥局 WAS：Endpoint 回應時間 P95", [
        app("latency", "P95", "開處方（扣庫存）", ep(EP_RX)),
        app("latency", "P95", "健保驗證", ep(EP_NHI)),
        app("latency", "P95", "檢驗報告", ep(EP_LAB)),
        app("latency", "P95", "查藥品", ep(EP_DRUG)),
        app("latency", "P95", "調劑領藥", ep(EP_DISPENSE)),
    ], formatter="millis.detailed")
    L.chart("錯誤呼叫數（依服務）", [
        app("erroneousCalls", "SUM", "門診 his-outpatient", svc("his-outpatient")),
        app("erroneousCalls", "SUM", "藥局 his-pharmacy", svc("his-pharmacy")),
        app("erroneousCalls", "SUM", "資料庫 HISDB", svc("HISDB")),
    ])
    L.chart("HTTP 狀態碼（左：5xx / 4xx，右：2xx）", [
        app("calls", "SUM", "5xx", http_status(500, 599)),
        app("calls", "SUM", "4xx", http_status(400, 499)),
    ], y2=[app("calls", "SUM", "2xx", http_status(200, 299))])
    L.chart("資料庫呼叫（JDBC → HISDB）", [
        app("latency", "MEAN", "SQL 平均延遲", svc("HISDB")),
        app("latency", "P95", "SQL P95 延遲", svc("HISDB")),
    ], formatter="millis.detailed", y2=[app("calls", "PER_SECOND", "SQL 每秒呼叫", svc("HISDB"))])
    L.chart("JVM Heap 使用率", [
        infra("jvmRuntimePlatform", "memory.usedPercentage", "MAX", "門診 WAS", "hospital-was-his"),
        infra("jvmRuntimePlatform", "memory.usedPercentage", "MAX", "藥局 WAS", "hospital-was-pharmacy"),
    ], formatter="percentage.detailed")
    L.chart("JVM GC 時間（global / scavenge）", [
        infra("jvmRuntimePlatform", "gc.global.time", "MAX", "門診 global GC", "hospital-was-his"),
        infra("jvmRuntimePlatform", "gc.scavenge.time", "MAX", "門診 scavenge GC", "hospital-was-his"),
        infra("jvmRuntimePlatform", "gc.global.time", "MAX", "藥局 global GC", "hospital-was-pharmacy"),
        infra("jvmRuntimePlatform", "gc.scavenge.time", "MAX", "藥局 scavenge GC", "hospital-was-pharmacy"),
    ], formatter="millis.detailed")
    L.chart("JVM 執行緒狀態", [
        infra("jvmRuntimePlatform", "threads.blocked", "MAX", "門診 Blocked", "hospital-was-his"),
        infra("jvmRuntimePlatform", "threads.waiting", "MAX", "門診 Waiting", "hospital-was-his"),
        infra("jvmRuntimePlatform", "threads.blocked", "MAX", "藥局 Blocked", "hospital-was-pharmacy"),
        infra("jvmRuntimePlatform", "threads.waiting", "MAX", "藥局 Waiting", "hospital-was-pharmacy"),
    ])
    L.markdown("顧問建議：AP 告警門檻",
               "| 指標 | 建議門檻 | 說明 |\n|---|---|---|\n"
               "| Endpoint P95 | 超過基準值 2 倍（Smart Alert 動態基準） | 交易量有日夜差異，用動態基準比固定值準 |\n"
               "| Endpoint 錯誤率 | > 1% 持續 5 分鐘 | 搭配 Trace 的例外堆疊直接定位程式 |\n"
               "| 下游呼叫錯誤（健保、藥局） | 任何 5xx 連續 3 分鐘 | 區分是自己還是外部系統的問題 |\n"
               "| Heap 使用率（GC 後） | > 85% 持續 10 分鐘 | GC 後還降不下來 = 疑似記憶體洩漏 |\n"
               "| GC 時間佔比 | > 10% | 應用程式停頓，使用者會感覺卡頓 |", height=9)
    return dashboard("仁心醫院 HIS｜2. AP 應用維運", L)


# ---------------------------------------------------------------- 3. 中介軟體 IHS / WAS
def middleware():
    L = Layout()
    W = "webSphereApplicationContainer"
    L.markdown("給誰看、怎麼看",
               "### ⚙️ 仁心醫院 HIS｜中介軟體（IHS / WAS）\n"
               "**對象**：Infra、WAS / IHS 管理員　**用途**：判斷「資源池是不是滿了」— 這是 WAS 環境最常見的效能瓶頸。\n\n"
               "請求路徑：**IHS（Busy Workers）→ WAS WebContainer Thread Pool → JDBC Connection Pool → Db2**。"
               "任何一層的池子用到 100%，前面一層就會開始排隊。Hung Thread 出現代表有請求卡住超過 20 秒。", height=6)
    L.chart("IHS：每秒請求數", [infra("httpd", "requests", "MEAN", "IHS requests/s", "hospital-ihs")])
    L.chart("IHS：Worker 使用狀況（MaxRequestWorkers 150）", [
        infra("httpd", "busy_workers", "MAX", "Busy Workers", "hospital-ihs"),
        infra("httpd", "worker.idle", "MIN", "Idle Workers", "hospital-ihs"),
    ])
    L.chart("WAS WebContainer Thread Pool 使用率（%）", [
        infra(W, "threadPools.WebContainer.usedPoolPercentage", "MAX", "門診 WAS（max 20）", "hospital-was-his"),
        infra(W, "threadPools.WebContainer.usedPoolPercentage", "MAX", "藥局 WAS（max 30）", "hospital-was-pharmacy"),
    ])
    L.chart("WAS WebContainer Active Threads / Hung Threads", [
        infra(W, "threadPools.WebContainer.activeThreads", "MAX", "門診 Active", "hospital-was-his"),
        infra(W, "threadPools.WebContainer.activeThreads", "MAX", "藥局 Active", "hospital-was-pharmacy"),
    ], y2=[
        infra(W, "threadPools.WebContainer.concurrentlyHungThreads", "MAX", "門診 Hung", "hospital-was-his"),
        infra(W, "threadPools.WebContainer.concurrentlyHungThreads", "MAX", "藥局 Hung", "hospital-was-pharmacy"),
    ])
    L.chart("JDBC Connection Pool 使用率（%，jdbc/HISDB，max 15）", [
        infra(W, "datasources.HISDB.usedPoolPercentage", "MAX", "門診 WAS", "hospital-was-his"),
        infra(W, "datasources.HISDB.usedPoolPercentage", "MAX", "藥局 WAS", "hospital-was-pharmacy"),
    ])
    L.chart("JDBC：等待連線的執行緒 / 平均等待時間", [
        infra(W, "datasources.HISDB.waitingThreadCount", "MAX", "門診 等待中", "hospital-was-his"),
        infra(W, "datasources.HISDB.waitingThreadCount", "MAX", "藥局 等待中", "hospital-was-pharmacy"),
    ], y2=[
        infra(W, "datasources.HISDB.averageWaitTime", "MAX", "門診 平均等待(ms)", "hospital-was-his"),
        infra(W, "datasources.HISDB.averageWaitTime", "MAX", "藥局 平均等待(ms)", "hospital-was-pharmacy"),
    ])
    L.chart("WAS Servlet（Web Module）平均回應時間", [
        infra(W, "servlets.his#his.war.avgResponseTime", "MAX", "his.war", "hospital-was-his"),
        infra(W, "servlets.pharmacy#pharmacy.war.avgResponseTime", "MAX", "pharmacy.war", "hospital-was-pharmacy"),
    ], formatter="millis.detailed")
    L.chart("WAS 交易（Transaction）", [
        infra(W, "transactions.activeCount", "MAX", "門診 Active Tx", "hospital-was-his"),
        infra(W, "transactions.activeCount", "MAX", "藥局 Active Tx", "hospital-was-pharmacy"),
    ])
    L.markdown("顧問建議：中介軟體告警門檻",
               "| 指標 | 轉黃 | 轉紅 | 處理方向 |\n|---|---|---|---|\n"
               "| IHS Busy Workers / MaxRequestWorkers | > 70% | > 90% | 後端變慢的前兆；先查 WAS，不要急著加大 IHS |\n"
               "| WebContainer Thread Pool 使用率 | > 80% | 100% 持續 3 分鐘 | 搭配 Trace 找出卡住的下游呼叫 |\n"
               "| Hung Threads | ≥ 1 | ≥ 3 | 抓 javacore，檢查是否在等外部系統或 DB 鎖 |\n"
               "| JDBC Pool 使用率 | > 80% | 100% | 檢查連線洩漏（Pool 滿但 DB 端閒置）或慢 SQL |\n"
               "| JDBC 等待執行緒 | > 0 持續 1 分鐘 | > 5 | 等待超過 connectionTimeout(10s) 就會丟例外 |", height=9)
    return dashboard("仁心醫院 HIS｜3. 中介軟體 IHS / WAS", L)


# ---------------------------------------------------------------- 4. DBA（Db2）
def dba():
    L = Layout()
    D = "db2Database"
    L.markdown("給誰看、怎麼看",
               "### 🗄️ 仁心醫院 HIS｜DBA（Db2 HISDB）\n"
               "**對象**：DBA　**用途**：區分「DB 本身有問題」還是「應用程式用錯 DB」。\n\n"
               "看法：**Rows Read / Rows Returned** 暴增 = 全表掃描（缺索引）；**Physical Reads** 上升 = Buffer Pool 不夠或命中率下降；"
               "**Lock Wait / Timeout / Deadlock** = 交易設計或批次作業互相卡住。右下角是應用端看到的 SQL 延遲（依資料表）。", height=6)
    L.chart("Rows Read vs Rows Returned（讀取效率）", [
        infra(D, "databases.rowsRead", "MEAN", "Rows Read", "hospital-db2"),
    ], y2=[infra(D, "databases.rowsReturned", "MEAN", "Rows Returned", "hospital-db2")])
    L.chart("Buffer Pool：邏輯讀 vs 實體讀（Data）", [
        infra(D, "bufferpools.dataLogicalReads", "MEAN", "Data Logical Reads", "hospital-db2"),
    ], y2=[infra(D, "bufferpools.dataPhysicalReads", "MEAN", "Data Physical Reads", "hospital-db2")])
    L.chart("鎖定：Active Lock Waits / Lock Wait Time", [
        infra(D, "vmonlockstats.activeLockWaits", "MAX", "Active Lock Waits", "hospital-db2"),
    ], y2=[infra(D, "vmonlockstats.lockWaitTime", "MAX", "Lock Wait Time (ms)", "hospital-db2")])
    L.chart("Deadlocks / Lock Timeouts", [
        infra(D, "workloadstats.deadlocks", "MAX", "Deadlocks", "hospital-db2"),
        infra(D, "workloadstats.lockTimeouts", "MAX", "Lock Timeouts", "hospital-db2"),
    ])
    L.chart("交易：Commits / Rollbacks", [
        infra(D, "databases.commits", "MEAN", "Commits", "hospital-db2"),
    ], y2=[infra(D, "workloadstats.appRollback", "MEAN", "Rollbacks", "hospital-db2")])
    L.chart("連線數 / Index 實體讀", [
        infra(D, "vmonlockstats.numberOfConnections", "MAX", "連線數", "hospital-db2"),
    ], y2=[infra(D, "bufferpools.indexPhysicalReads", "MEAN", "Index Physical Reads", "hospital-db2")])
    L.chart("Tablespace 使用率（%，USERSPACE1）", [
        infra(D, "tablespaceutil.USERSPACE1.spaceUtilPercent", "MAX", "USERSPACE1", "hospital-db2"),
    ])
    L.chart("應用端 SQL 延遲 P95（依資料表）", [
        app("latency", "P95", "MEDICAL_RECORDS（病歷）", ep("HIS.MEDICAL_RECORDS", "HISDB")),
        app("latency", "P95", "DRUGS（藥品庫存）", ep("HIS.DRUGS", "HISDB")),
        app("latency", "P95", "PRESCRIPTIONS（處方）", ep("HIS.PRESCRIPTIONS", "HISDB")),
        app("latency", "P95", "APPOINTMENTS（掛號）", ep("HIS.APPOINTMENTS", "HISDB")),
    ], formatter="millis.detailed")
    L.markdown("顧問建議：DBA 告警門檻",
               "| 指標 | 建議門檻 | 說明 |\n|---|---|---|\n"
               "| Buffer Pool 命中率 = 1 −（Physical ÷ Logical） | < 95%（OLTP） | 命中率下降先查是否有全表掃描，再考慮加大 BP |\n"
               "| Rows Read ÷ Rows Returned | > 100 | 讀很多只回一點 = 缺索引或 SQL 寫法不佳 |\n"
               "| Lock Timeouts | > 0 | LOCKTIMEOUT=20s；有逾時代表有交易被卡住 |\n"
               "| Deadlocks | > 0 | 檢查交易更新順序是否一致 |\n"
               "| Active Lock Waits | > 5 持續 1 分鐘 | 找出持有鎖的長交易 / 批次 |\n"
               "| Tablespace 使用率 | > 80% | 預留擴充或歸檔舊病歷 |", height=9)
    return dashboard("仁心醫院 HIS｜4. DBA Db2", L)


# ---------------------------------------------------------------- 5. JVM 效能（tWAS / IBM J9）
def jvm():
    L = Layout()
    J, P = "jvmRuntimePlatform", "process"
    WAS = [("門診", "hospital-was-his"), ("藥局", "hospital-was-pharmacy")]

    def both(etype, metric, agg, suffix=""):
        return [infra(etype, metric, agg, f"{name}{suffix}", dep) for name, dep in WAS]

    L.markdown("給誰看、怎麼看",
               "### ☕ 仁心醫院 HIS｜JVM 效能（tWAS 9.0.5 / IBM J9 Java 8）\n"
               "**對象**：AP 開發、WAS 管理員、效能調校　**用途**：判斷變慢是不是 JVM 本身造成 — CPU、Heap、GC 停頓、執行緒。\n\n"
               "看法：**CPU 高** → 到 Analytics → Profiles 看 CPU 火焰圖找熱點方法；**GC 後 Heap 一直往上** → 記憶體洩漏；"
               "**GC 停頓時間高** → 使用者感覺卡頓；**Blocked 執行緒多** → 鎖競爭（看 Contention profile）。"
               "IBM J9 使用 gencon：新生代 = nursery（allocate / survivor），老年代 = tenured（SOA / LOA）。", height=7)
    for name, dep in WAS:
        L.big(f"{name} Heap 使用率", infra(J, "memory.usedPercentage", "MAX", "Heap %", dep), formatter="percentage.detailed")
        L.big(f"{name} GC 後 Heap", infra(J, "memory.gc.afterPercentage", "MAX", "GC 後 Heap %", dep), formatter="percentage.detailed")
    for name, dep in WAS:
        L.big(f"{name} JVM CPU（user）", infra(P, "cpu.user", "MAX", "CPU user", dep), formatter="percentage.detailed")
        L.big(f"{name} Blocked 執行緒", infra(J, "threads.blocked", "MAX", "Blocked", dep))

    L.chart("JVM 程序 CPU（user / sys）", both(P, "cpu.user", "MAX", " user") + both(P, "cpu.sys", "MAX", " sys"),
            formatter="percentage.detailed")
    L.chart("Heap 使用率 vs GC 後 Heap（%）", both(J, "memory.usedPercentage", "MAX", " 使用中"),
            formatter="percentage.detailed", y2=both(J, "memory.gc.afterPercentage", "MAX", " GC 後"),
            y2_formatter="percentage.detailed")
    L.chart("Heap 用量（Used / Max -Xmx768m）", both(J, "memory.used", "MAX", " Used") + both(J, "memory.max", "MAX", " Max"),
            formatter="bytes.detailed")
    L.chart("GC 停頓時間（global / scavenge）",
            both(J, "gc.global.time", "MAX", " global") + both(J, "gc.scavenge.time", "MAX", " scavenge"),
            formatter="millis.detailed", y2=both(J, "suspension.time", "MAX", " 總停頓"), y2_formatter="millis.detailed")
    L.chart("GC 次數（global / scavenge）",
            both(J, "gc.global.inv", "MAX", " global") + both(J, "gc.scavenge.inv", "MAX", " scavenge"))
    L.chart("J9 Heap 記憶體池：tenured（老年代）", both(J, "pools.tenured-SOA", "MAX", " tenured-SOA")
            + both(J, "pools.tenured-LOA", "MAX", " tenured-LOA"), formatter="bytes.detailed")
    L.chart("J9 Heap 記憶體池：nursery（新生代）", both(J, "pools.nursery-allocate", "MAX", " allocate")
            + both(J, "pools.nursery-survivor", "MAX", " survivor"), formatter="bytes.detailed")
    L.chart("Non-Heap：class storage / JIT code cache", both(J, "pools.class storage", "MAX", " class storage")
            + both(J, "pools.JIT code cache", "MAX", " JIT code cache"), formatter="bytes.detailed")
    L.chart("執行緒狀態（Runnable / Blocked / Waiting）",
            both(J, "threads.runnable", "MAX", " Runnable") + both(J, "threads.blocked", "MAX", " Blocked"),
            y2=both(J, "threads.waiting", "MAX", " Waiting") + both(J, "threads.timed-waiting", "MAX", " Timed-waiting"))
    L.chart("程序記憶體 RSS / 開啟檔案數", both(P, "mem.resident", "MAX", " RSS"), formatter="bytes.detailed",
            y2=both(P, "openFiles.current", "MAX", " open files"))
    L.chart("對照：服務每秒呼叫數 / P95 回應時間", [
        app("calls", "PER_SECOND", "門診 calls/s", svc("his-outpatient")),
        app("calls", "PER_SECOND", "藥局 calls/s", svc("his-pharmacy")),
    ], y2=[
        app("latency", "P95", "門診 P95", svc("his-outpatient")),
        app("latency", "P95", "藥局 P95", svc("his-pharmacy")),
    ], y2_formatter="millis.detailed", width=12)
    L.markdown("顧問建議：JVM 告警門檻與調校",
               "| 指標 | 轉黃 | 轉紅 | 處理方向 |\n|---|---|---|---|\n"
               "| GC 後 Heap 使用率 | > 70% | > 85% 持續 10 分鐘 | 持續上升 = 記憶體洩漏，抓 heapdump；穩定偏高則調大 -Xmx |\n"
               "| GC 停頓時間佔比 | > 5% | > 10% | 調整 nursery 大小（-Xmn）或 Heap 大小 |\n"
               "| global GC 次數 | 每分鐘 > 1 | 每分鐘 > 5 | tenured 不足，物件太早晉升或洩漏 |\n"
               "| JVM CPU | > 70% | > 90% | 看 CPU Profile 火焰圖找熱點方法 |\n"
               "| Blocked 執行緒 | > 5 | > 20 | 看 Contention Profile / javacore 找鎖 |\n"
               "| class storage 持續成長 | — | 不斷上升 | Classloader 洩漏（常見於重複部署） |\n\n"
               "**Profiles**：Analytics → Applications 麵包屑 → **Profiles**，可看 CPU 與 Contention 火焰圖（`chaos.sh on cpu-burn` 可示範）。",
               height=11)
    return dashboard("仁心醫院 HIS｜5. JVM 效能", L)


if __name__ == "__main__":
    for key, fn in [("1-executive", executive), ("2-ap", ap), ("3-middleware", middleware), ("4-dba", dba),
                    ("5-jvm", jvm)]:
        d = fn()
        (OUT / f"{key}.json").write_text(json.dumps(d, ensure_ascii=False, indent=1))
        print(f"{key}.json  widgets={len(d['widgets'])}  title={d['title']}")
