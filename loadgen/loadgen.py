#!/usr/bin/env python3
"""仁心醫院 HIS 模擬流量：門診櫃台、醫師、藥局人員的日常操作（只用標準函式庫）。

環境變數：
  TARGET   IHS 位址（預設 http://hospital-ihs:8080）
  WORKERS  同時模擬的使用者數（預設 6）
  THINK    每個動作之間的平均等待秒數（預設 0.8）
"""
import json
import os
import random
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

TARGET = os.environ.get("TARGET", "http://hospital-ihs:8080").rstrip("/")
WORKERS = int(os.environ.get("WORKERS", "6"))
THINK = float(os.environ.get("THINK", "0.8"))
DEPTS = ["FM01", "IM01", "CV01", "GI01", "PD01", "OR01", "ENT1", "DM01"]
DIAG = [("J06.9", "急性上呼吸道感染"), ("I10", "原發性高血壓"), ("E11.9", "第二型糖尿病"),
        ("K21.9", "胃食道逆流"), ("M54.5", "下背痛"), ("J30.4", "過敏性鼻炎"), ("R51", "頭痛")]
LABS = ["", "", "", "CBC", "HBA1C", "LIPID", "ALT", "CRE"]

stats = Counter()
lock = threading.Lock()


def patient_id():
    # 約 8% 是沒有健保卡號的病患（PATIENT_ID 為 50 的倍數），npe-bug 情境才會被觸發
    if random.random() < 0.08:
        return random.randint(1, 600) * 50
    return random.randint(1, 30000)


def call(method, path, body=None, pid=None, name=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(TARGET + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "hospital-loadgen/1.0")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if pid is not None:
        req.add_header("X-Patient-Id", str(pid))
    key = name or path
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            payload = r.read()
            with lock:
                stats[f"{key} {r.status}"] += 1
            return json.loads(payload or b"{}")
    except urllib.error.HTTPError as e:
        with lock:
            stats[f"{key} {e.code}"] += 1
    except Exception as e:  # 連線失敗、逾時
        with lock:
            stats[f"{key} {type(e).__name__}"] += 1
    return None


def outpatient_visit():
    """完整門診流程：掛號 → 看診清單 → 看診完成（病歷 + 處方 + 檢驗）"""
    pid = patient_id()
    appt = call("POST", "/his/api/appointments", {"patientId": pid, "deptCode": random.choice(DEPTS)},
                pid, "掛號")
    if not appt or "apptId" not in appt:
        return
    call("GET", f"/his/api/appointments/queue?doctorId={appt['doctorId']}", None, pid, "看診清單")
    time.sleep(random.uniform(0.2, 1.0))
    icd, diag = random.choice(DIAG)
    items = [{"drugId": d, "qty": random.randint(1, 3), "days": random.choice([3, 7, 14, 28])}
             for d in random.sample(range(1, 31), random.randint(1, 3))]
    call("POST", f"/his/api/visits/{appt['apptId']}/complete",
         {"icd10": icd, "diagnosis": diag, "items": items, "lab": random.choice(LABS)}, pid, "看診完成")


def read_records():
    pid = patient_id()
    call("GET", f"/his/api/patients/{pid}/records", None, pid, "查病歷")


def read_patient():
    pid = patient_id()
    call("GET", f"/his/api/patients/{pid}", None, pid, "查病患")


def read_drug():
    call("GET", f"/pharmacy/api/drugs/{random.randint(1, 200)}", None, None, "查藥品")


def read_lab():
    pid = patient_id()
    call("GET", f"/pharmacy/api/lab/patients/{pid}", None, pid, "查檢驗")


def search_patient():
    """櫃台用病歷號或姓氏查病患"""
    q = random.choice(["A%09d" % random.randint(1, 30000), random.choice("陳林黃張李王吳劉蔡楊")])
    call("GET", "/his/api/patients/search?q=" + urllib.parse.quote(q), None, None, "查詢病患")


def dispense():
    """藥師調劑：取最早的待調劑處方並完成領藥"""
    r = call("GET", "/pharmacy/api/prescriptions?status=NEW&limit=5", None, None, "待調劑清單")
    if r and r.get("prescriptions"):
        p = r["prescriptions"][0]
        call("POST", f"/pharmacy/api/prescriptions/{p['rxId']}/dispense", {}, p["patientId"], "調劑完成")


def dashboard_poll():
    """網頁首頁的今日統計"""
    call("GET", "/his/api/stats/today", None, None, "今日統計")


ACTIONS = [(outpatient_visit, 25), (read_records, 25), (read_patient, 15), (read_drug, 8), (read_lab, 8),
           (search_patient, 8), (dispense, 8), (dashboard_poll, 3)]


def worker():
    funcs, weights = zip(*ACTIONS)
    while True:
        f = random.choices(funcs, weights)[0]
        f()
        time.sleep(random.expovariate(1.0 / THINK))


def reporter():
    while True:
        time.sleep(30)
        with lock:
            snap = dict(stats)
            stats.clear()
        print(time.strftime("%H:%M:%S"), json.dumps(snap, ensure_ascii=False, sort_keys=True), flush=True)


if __name__ == "__main__":
    print(f"loadgen target={TARGET} workers={WORKERS} think={THINK}s", flush=True)
    threading.Thread(target=reporter, daemon=True).start()
    for _ in range(WORKERS):
        threading.Thread(target=worker, daemon=True).start()
        time.sleep(0.5)
    while True:
        time.sleep(3600)
