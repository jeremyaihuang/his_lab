# 仁心醫院 HIS — IBM Instana 可觀測性展示環境

模擬醫院門診系統，技術棧為 **IBM HTTP Server + WebSphere Application Server traditional + Db2**，跑在 Kubernetes 上，
用來展示 Instana 對 IHS / tWAS / Db2 的指標監控、分散式追蹤、錯誤情境與依角色劃分的 Custom Dashboard。

## 架構

```
hospital-loadgen（模擬櫃台、醫師、藥局人員操作）
   │
   ▼  NodePort 30480
hospital-ihs        IBM HTTP Server 9.0.5.25 + WebSphere Plugin   ← Instana：mod_status 指標
   │ plugin-cfg.xml：/his/* → WAS-HIS、/pharmacy/* → WAS-Pharmacy
   ▼
hospital-was-his    tWAS 9.0.5.29（掛號 / 病歷 / 看診）            ← Instana：trace + JVM + PMI
   │ HTTP（HttpURLConnection，trace 自動串接）
   ▼
hospital-was-pharmacy tWAS 9.0.5.29（處方 / 庫存 / 檢驗 / 健保閘道）← Instana：trace + JVM + PMI
   │ JDBC（WAS DataSource jdbc/HISDB，Connection Pool max 15）
   ▼
hospital-db2        Db2 12.1 Community，資料庫 HISDB              ← Instana：Db2 sensor
                    30,000 病患 / 600,000 病歷 / 100,000 檢驗單 / 200 種藥品
```

### 交易

| 交易 | 路徑 |
|---|---|
| 掛號 | IHS → `POST /his/api/appointments` → 健保卡驗證 `GET /pharmacy/api/nhi/verify` → DB2 |
| 查病歷 | IHS → `GET /his/api/patients/{id}/records` → DB2（病歷大表）+ `GET /pharmacy/api/lab/patients/{id}` |
| 看診完成 | IHS → `POST /his/api/visits/{apptId}/complete` → DB2 → `POST /pharmacy/api/prescriptions`（交易內扣庫存）→ `POST /pharmacy/api/lab/orders` |
| 查病患 / 藥品 / 檢驗 | `GET /his/api/patients/{id}`、`GET /pharmacy/api/drugs/{id}`、`GET /pharmacy/api/lab/patients/{id}` |

所有請求帶 `X-Patient-Id` header，Instana Agent 設定會擷取到 trace 中。

## 目錄

| 路徑 | 內容 |
|---|---|
| `apps/` | Java EE 7 應用程式（Maven）：`common`（情境開關、JDBC、HTTP）、`his`、`pharmacy` |
| `was/` | tWAS image：`Dockerfile`、`hospital.py`（wsadmin：DataSource、Pool、Thread Pool、Hung Thread、PMI、安裝 App） |
| `ihs/` | IHS image：`Dockerfile`、`hospital.conf`（mod_status、MPM）、`plugin-cfg.xml` |
| `db2/` | `hisdb.sql`（schema + 示範資料）、`init-hisdb.sh`（首次初始化、監控開關、LOCKTIMEOUT 20s） |
| `loadgen/` | `loadgen.py` 模擬流量（以 ConfigMap 掛進 `python:3.12-slim`） |
| `k8s/` | 部署檔：`10-db2.yaml`、`20-apps.yaml`、`30-loadgen.yaml`、`node-importer.yaml` |
| `instana/` | `configuration-hospital.yaml`（Db2 / WebSphere / HTTPd sensor 設定） |
| `scripts/` | `build-images.sh`、`import-images.sh`、`deploy.sh`、`instana-agent-config.sh`、`chaos.sh` |
| `hospital.env` | 示範環境帳密與 image tag |

## 部署

```bash
scripts/build-images.sh          # 本機 build hospital/was-his、hospital/was-pharmacy、hospital/ihs
SSHPASS=... scripts/import-images-ssh.sh   # 透過 SSH 匯入每台 worker 的 containerd（或 scripts/import-images.sh：privileged DaemonSet）
scripts/deploy.sh                # DB2 → WAS ×2 → IHS → loadgen，並更新 Instana Agent 設定
```

入口：`http://<任一節點 IP>:30480/`

IHS 安裝檔 `9.0.5-WS-IHS-ARCHIVE-linux-x86_64-FP025.zip` 需放在專案根目錄（IBM 授權軟體，不要推到公開 registry）。

## 醫院網頁

入口：`http://<任一節點 IP>:30480/`（IHS 提供靜態網頁，`ihs/htdocs/`，API 經 WebSphere Plugin 轉到 WAS）

| 頁籤 | 功能 | 呼叫的 API |
|---|---|---|
| 🧾 櫃台掛號 | 以病歷號 / 編號 / 姓名查病患 → 選科別 → 掛號（含健保卡驗證），顯示看診號碼 | `GET /his/api/patients/search`、`POST /his/api/appointments` |
| 🩺 醫師看診 | 選科別、醫師 → 今日看診清單 → 看過去病歷與檢驗 → 寫診斷、開藥、開檢驗 → 完成看診 | `GET /his/api/doctors`、`GET /his/api/appointments/queue`、`GET /his/api/patients/{id}/records`、`POST /his/api/visits/{apptId}/complete` |
| 💊 藥局調劑 | 待調劑處方 → 調劑完成；藥品庫存查詢 | `GET /pharmacy/api/prescriptions`、`POST /pharmacy/api/prescriptions/{rxId}/dispense`、`GET /pharmacy/api/drugs` |
| 📋 病歷查詢 | 查病患完整就診紀錄與檢驗報告 | `GET /his/api/patients/{id}/records` |

頂部顯示今日掛號 / 已看診 / 候診 / 待領藥；底部狀態列顯示最近一次 API 的回應碼與回應時間，
錯誤情境開啟時網頁會直接跳出錯誤訊息（例如 `npe-bug` 時，病患 150 掛號會看到 HTTP 500）。

## Instana Dashboard

| Dashboard | ID |
|---|---|
| 1. 主管營運總覽 | `EMSYwnalR6usS-WFehSF4w` |
| 2. AP 應用維運 | `RezGifN0QIaoYb2Ve36RmQ` |
| 3. 中介軟體 IHS / WAS | `HS8Nj6rYQGKaPMEBwveNYQ` |
| 4. DBA Db2 | `0HRGTSrHTG64zJF7MKX2Eg` |
| 5. JVM 效能（tWAS IBM J9） | `VqagYD1gS9WJVk_w17-pRA` |

Application Perspective「仁心醫院 HIS」：`y0q3DYIfRxWbVRD-aXmLEQ`。定義在 `instana/dashboards/build_dashboards.py`，
修改後執行 `scripts/update-dashboards.sh y0q3DYIfRxWbVRD-aXmLEQ` 更新；`instana/dashboards/check_dashboards.py` 可檢查每條指標是否查得到資料。

## 錯誤情境

網頁控制台：`http://<node>:30480/ops/chaos.html`（僅內網 IP 可存取），可開關所有應用程式情境並調整數值；平台情境（pharmacy-down / db2-down / was-crash）仍需用下方指令。

```bash
scripts/chaos.sh list
scripts/chaos.sh on slow-sql
scripts/chaos.sh off slow-sql
scripts/chaos.sh reset
```

| 情境 | 位置 | 現象 | 在 Instana 看哪裡 |
|---|---|---|---|
| `slow-sql` | HIS | 刪掉病歷索引 + Buffer Pool 縮成 1000 頁 → 全表掃描 | 查病歷延遲、Db2 Rows Read、Buffer Pool 實體讀取 |
| `npe-bug` | HIS | 無健保卡病患掛號 NullPointerException | 掛號 Endpoint 錯誤率、Trace 例外堆疊 |
| `register-error [%]` | HIS | 掛號隨機 HTTP 500 | 錯誤率、SLO |
| `mem-leak [KB]` | HIS（`pharmacy:` 可指定） | Heap 持續成長、GC 變頻繁 → OOM | JVM Heap / GC |
| `conn-leak` | HIS（`pharmacy:` 可指定） | JDBC 連線不歸還 → Pool 耗盡 | WAS JDBC Pool Percent Used / Threads Waiting |
| `cpu-burn [ms]` | HIS（`pharmacy:` 可指定） | 每個請求多耗 CPU | Container CPU、延遲 |
| `db-lock [秒]` | Pharmacy | 背景批次鎖住常用藥品 → Lock Wait、20 秒 Lock Timeout | Db2 Lock Waits / Timeouts、處方 500 |
| `deadlock` | Pharmacy | 更新庫存順序不一致 → Deadlock | Db2 Deadlocks、SQLCODE -911 |
| `slow-downstream [秒]` | Pharmacy | 健保閘道延遲 → HIS WebContainer 執行緒卡住 | WAS Thread Pool Active / Hung Threads |
| `nhi-down` | Pharmacy | 健保閘道 503 → 掛號 502 | 下游錯誤、Incident |
| `pharmacy-down` | K8s | 藥局 WAS 0 個 Pod | 呼叫失敗、IHS Plugin 錯誤 |
| `db2-down` | K8s | `db2stop force` | 全面 JDBC 錯誤 |
| `was-crash` | K8s | 砍掉 WAS Java 程序 → 容器重啟 | Pod 重啟、可用性 |

開關情境時會送 Instana 自訂事件（`[CHAOS] xxx ON/OFF`）到 Agent，方便在時間軸上對照。

## 帳密（僅示範環境）

先 `cp hospital.env.example hospital.env` 並填入密碼（hospital.env 不進 git）。IBM 授權檔需自行放入：`9.0.5-WS-IHS-ARCHIVE-linux-x86_64-FP025.zip`（專案根目錄）與 `was/lib/db2jcc4.jar`。

| 用途 | 帳號 | 密碼 |
|---|---|---|
| Db2 instance | `db2inst1` | `hospital.env` 的 `DB2_PASSWORD` |
| WAS 管理主控台 / Instana WebSphere sensor | `WAS_ADMIN_USER` | `WAS_ADMIN_PASSWORD` |
