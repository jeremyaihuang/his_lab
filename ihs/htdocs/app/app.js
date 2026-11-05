/* 仁心醫院 HIS 前端：櫃台掛號 / 醫師看診 / 藥局調劑 / 病歷查詢（原生 JS，透過 IHS 呼叫 WAS API） */
(function () {
  "use strict";

  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const ICD = [
    ["J06.9", "急性上呼吸道感染"], ["I10", "原發性高血壓"], ["E11.9", "第二型糖尿病"], ["K21.9", "胃食道逆流"],
    ["M54.5", "下背痛"], ["L30.9", "皮膚炎"], ["J30.4", "過敏性鼻炎"], ["R51", "頭痛"],
  ];
  const LABS = [["", "不開檢驗"], ["CBC", "全血球計數"], ["HBA1C", "糖化血色素"], ["LIPID", "血脂肪"], ["ALT", "肝功能 ALT"], ["CRE", "腎功能 Creatinine"]];
  let departments = [];

  // ---------------------------------------------------------------- API
  async function api(method, path, body, patientId) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (patientId) headers["X-Patient-Id"] = String(patientId);
    const t0 = performance.now();
    let res, text;
    try {
      res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
      text = await res.text();
    } catch (e) {
      status(method, path, 0, performance.now() - t0);
      throw new ApiError(0, "無法連線到伺服器：" + e.message);
    }
    const ms = performance.now() - t0;
    status(method, path, res.status, ms);
    let data = null;
    try { data = text ? JSON.parse(text) : {}; } catch (e) { /* WAS 預設錯誤頁是純文字 */ }
    if (!res.ok) throw new ApiError(res.status, (data && data.error) || text.slice(0, 300) || res.statusText);
    return data;
  }

  class ApiError extends Error {
    constructor(code, msg) { super(msg); this.code = code; }
  }

  function status(method, path, code, ms) {
    const el = $("#sb-last");
    const cls = code === 0 || code >= 500 ? "fail" : ms > 2000 ? "slow" : "";
    el.innerHTML = `最近一次 API：<span class="${cls}">${esc(method)} ${esc(path)} → ${code || "連線失敗"}（${Math.round(ms)} ms）</span>`;
  }

  function showError(e, what) {
    const box = $("#alert");
    const code = e.code ? `HTTP ${e.code}` : "連線失敗";
    box.innerHTML = `<div><strong>${esc(what)}失敗（${esc(code)}）</strong><code>${esc(e.message)}</code></div><button class="close" aria-label="關閉">×</button>`;
    box.classList.remove("hidden");
    $(".close", box).onclick = () => box.classList.add("hidden");
  }
  const clearError = () => $("#alert").classList.add("hidden");

  async function busy(btn, fn) {
    if (btn) { btn.disabled = true; btn.classList.add("loading"); }
    try { return await fn(); } finally { if (btn) { btn.disabled = false; btn.classList.remove("loading"); } }
  }

  // ---------------------------------------------------------------- 共用
  function age(birth) {
    const b = new Date(birth), n = new Date();
    let a = n.getFullYear() - b.getFullYear();
    if (n < new Date(n.getFullYear(), b.getMonth(), b.getDate())) a--;
    return a;
  }
  const gender = (g) => (g === "F" ? "女" : "男");
  const deptName = (code) => (departments.find((d) => d.code === code) || {}).name || code;

  function patientCard(p) {
    return `<div class="patient-card">
      <div><div class="k">姓名</div><div class="v">${esc(p.name)}</div></div>
      <div><div class="k">病歷號</div><div class="v">${esc(p.chartNo)}</div></div>
      <div><div class="k">性別 / 年齡</div><div class="v">${gender(p.gender)} / ${age(p.birthDate)} 歲</div></div>
      <div><div class="k">健保</div><div class="v">${p.nhiCard ? `<span class="tag">健保 ${esc(p.nhiCard)}</span>` : `<span class="tag self">自費（無健保卡）</span>`}</div></div>
    </div>`;
  }

  function patientTable(list) {
    if (!list.length) return `<div class="empty">查無病患</div>`;
    return `<div class="table-wrap"><table><thead><tr><th>病歷號</th><th>姓名</th><th>性別</th><th>年齡</th><th>健保</th></tr></thead><tbody>
      ${list.map((p, i) => `<tr class="clickable" data-i="${i}"><td>${esc(p.chartNo)}</td><td>${esc(p.name)}</td><td>${gender(p.gender)}</td>
        <td>${age(p.birthDate)}</td><td>${p.nhiCard ? "健保" : `<span class="tag self">自費</span>`}</td></tr>`).join("")}
    </tbody></table></div>`;
  }

  // 兩個頁籤共用的病患搜尋
  function bindSearch(key, onPick) {
    const form = $(`form[data-search="${key}"]`);
    const box = $(`[data-results="${key}"]`);
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      clearError();
      const q = $("input", form).value.trim();
      try {
        const r = await busy($("button", form), () => api("GET", `/his/api/patients/search?q=${encodeURIComponent(q)}`));
        box.innerHTML = patientTable(r.patients);
        $$("tr.clickable", box).forEach((tr) => tr.addEventListener("click", () => {
          $$("tr", box).forEach((x) => x.classList.remove("selected"));
          tr.classList.add("selected");
          onPick(r.patients[Number(tr.dataset.i)]);
        }));
        if (r.patients.length === 1) $("tr.clickable", box).click();
      } catch (e) { showError(e, "查詢病患"); }
    });
    return form;
  }

  // ---------------------------------------------------------------- 頂部統計
  async function refreshStats() {
    try {
      const [s, rx] = await Promise.all([api("GET", "/his/api/stats/today"), api("GET", "/pharmacy/api/prescriptions?status=NEW&limit=50")]);
      $("#st-total").textContent = s.appointments;
      $("#st-done").textContent = s.done;
      $("#st-wait").textContent = s.waiting;
      $("#st-rx").textContent = rx.prescriptions.length >= 50 ? "50+" : rx.prescriptions.length;
    } catch (e) { /* 統計失敗不跳錯誤框，狀態列會顯示 */ }
  }
  function tickClock() {
    $("#clock").textContent = new Date().toLocaleString("zh-TW", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  // ---------------------------------------------------------------- 櫃台掛號
  let regPatient = null, regDept = null;
  function initRegister() {
    const form = bindSearch("register", (p) => {
      regPatient = p;
      $("#reg-patient").className = "";
      $("#reg-patient").innerHTML = patientCard(p);
      $("#reg-result").innerHTML = "";
      updateRegButton();
    });
    $$(".hint a").forEach((a) => a.addEventListener("click", (ev) => {
      ev.preventDefault();
      $("input", form).value = a.dataset.q;
      form.requestSubmit();
    }));
    $("#reg-depts").innerHTML = departments.map((d) => `<button class="dept" data-code="${esc(d.code)}">${esc(d.name)}</button>`).join("");
    $$("#reg-depts .dept").forEach((b) => b.addEventListener("click", () => {
      $$("#reg-depts .dept").forEach((x) => x.classList.remove("selected"));
      b.classList.add("selected");
      regDept = b.dataset.code;
      updateRegButton();
    }));
    $("#reg-submit").addEventListener("click", async (ev) => {
      clearError();
      try {
        const r = await busy(ev.currentTarget, () => api("POST", "/his/api/appointments", { patientId: regPatient.patientId, deptCode: regDept }, regPatient.patientId));
        const docs = await api("GET", `/his/api/doctors?dept=${encodeURIComponent(r.deptCode)}`);
        const doc = docs.doctors.find((d) => d.doctorId === r.doctorId) || { name: "#" + r.doctorId };
        $("#reg-result").innerHTML = `<div class="ticket">
          <div>${esc(deptName(r.deptCode))}｜${esc(doc.name)} 醫師</div>
          <div class="num">${esc(r.seqNo)} 號</div>
          <div>${esc(regPatient.name)}　${r.selfPay ? '<span class="tag self">自費</span>' : '<span class="tag">健保卡驗證通過</span>'}　掛號序號 #${esc(r.apptId)}</div>
        </div>`;
        refreshStats();
      } catch (e) { showError(e, "掛號"); }
    });
  }
  function updateRegButton() { $("#reg-submit").disabled = !(regPatient && regDept); }

  // ---------------------------------------------------------------- 醫師看診
  let docQueue = [];
  async function initDoctor() {
    const sel = $("#doc-dept");
    sel.innerHTML = departments.map((d) => `<option value="${esc(d.code)}">${esc(d.name)}</option>`).join("");
    sel.addEventListener("change", loadDoctors);
    $("#doc-doctor").addEventListener("change", loadQueue);
    $("#doc-refresh").addEventListener("click", loadQueue);
  }
  async function loadDoctors() {
    try {
      const r = await api("GET", `/his/api/doctors?dept=${encodeURIComponent($("#doc-dept").value)}`);
      $("#doc-doctor").innerHTML = r.doctors.map((d) => `<option value="${d.doctorId}">${esc(d.name)} 醫師（候診 ${d.waiting}）</option>`).join("");
      // 預設選候診最多的醫師
      const busiest = r.doctors.slice().sort((a, b) => b.waiting - a.waiting)[0];
      if (busiest) $("#doc-doctor").value = busiest.doctorId;
      loadQueue();
    } catch (e) { showError(e, "載入醫師清單"); }
  }
  async function loadQueue() {
    const id = $("#doc-doctor").value;
    if (!id) return;
    try {
      const r = await busy($("#doc-refresh"), () => api("GET", `/his/api/appointments/queue?doctorId=${id}`));
      docQueue = r.queue;
      const box = $("#doc-queue");
      if (!docQueue.length) { box.innerHTML = `<div class="empty">今日尚無掛號</div>`; return; }
      box.innerHTML = docQueue.map((q, i) => `<button class="q-item ${q.status === "DONE" ? "done" : ""}" data-i="${i}">
        <span class="seq">${q.seqNo}</span><span class="nm">${esc(q.name)}<br><small>${esc(q.chartNo)}</small></span>
        <span class="tag ${q.status === "DONE" ? "done" : "wait"}">${q.status === "DONE" ? "已看診" : "候診"}</span></button>`).join("");
      $$(".q-item", box).forEach((b) => b.addEventListener("click", () => {
        $$(".q-item", box).forEach((x) => x.classList.remove("selected"));
        b.classList.add("selected");
        openVisit(docQueue[Number(b.dataset.i)]);
      }));
    } catch (e) { showError(e, "載入看診清單"); }
  }

  async function openVisit(q) {
    clearError();
    const panel = $("#doc-visit");
    panel.innerHTML = `<div class="empty">載入病歷中…</div>`;
    const pid = Number(q.chartNo.slice(1));
    try {
      const [p, rec] = await Promise.all([api("GET", `/his/api/patients/${pid}`, undefined, pid), api("GET", `/his/api/patients/${pid}/records`, undefined, pid)]);
      const done = q.status === "DONE";
      panel.innerHTML = `
        <div class="card-head"><h2>第 ${q.seqNo} 號　${esc(p.name)}</h2>${done ? '<span class="tag done">已完成看診</span>' : ""}</div>
        ${patientCard(p)}
        <div class="grid two">
          <div>
            <h3>過去就診紀錄</h3>
            ${recordsTimeline(rec.records.slice(0, 6))}
            <h3>檢驗報告</h3>
            ${labTable(rec.lab ? rec.lab.orders : null, rec.labError)}
          </div>
          <div>
            <h3>本次看診</h3>
            <label class="field">診斷（ICD-10）<select id="v-icd">${ICD.map(([c, n]) => `<option value="${c}">${c}　${n}</option>`).join("")}</select></label>
            <label class="field">病歷記載<textarea id="v-notes" rows="3">主訴症狀數日，理學檢查如上，給予藥物治療並安排回診。</textarea></label>
            <div class="field">處方用藥（藥品／每次劑量／天數）</div>
            <div id="v-items"></div>
            <button id="v-add" class="btn small" type="button">＋ 新增藥品</button>
            <label class="field" style="margin-top:12px">檢驗<select id="v-lab">${LABS.map(([c, n]) => `<option value="${c}">${n}</option>`).join("")}</select></label>
            <button id="v-submit" class="btn primary big" ${done ? "disabled" : ""}>完成看診（寫病歷、開處方）</button>
            <div id="v-result"></div>
          </div>
        </div>`;
      addDrugRow(); addDrugRow();
      $("#v-add").addEventListener("click", addDrugRow);
      $("#v-submit").addEventListener("click", (ev) => submitVisit(ev.currentTarget, q, pid));
    } catch (e) {
      panel.innerHTML = `<div class="empty">無法載入病歷</div>`;
      showError(e, "載入病歷");
    }
  }

  function addDrugRow() {
    const row = document.createElement("div");
    row.className = "rx-items";
    row.innerHTML = `<div class="drug-pick"><input placeholder="輸入藥名搜尋，例如 Amlo" class="d-name"><div class="suggest hidden"></div></div>
      <input type="number" class="d-qty" min="1" value="1" title="每次劑量"><input type="number" class="d-days" min="1" value="7" title="天數">
      <button class="btn small d-del" type="button" title="移除">✕</button>`;
    $("#v-items").appendChild(row);
    const input = $(".d-name", row), sug = $(".suggest", row);
    let timer;
    input.addEventListener("input", () => {
      row.dataset.drugId = "";
      clearTimeout(timer);
      const q = input.value.trim();
      if (q.length < 2) { sug.innerHTML = ""; return; }
      timer = setTimeout(async () => {
        try {
          const r = await api("GET", `/pharmacy/api/drugs?q=${encodeURIComponent(q)}`);
          sug.innerHTML = r.drugs.slice(0, 8).map((d) => `<div data-id="${d.drugId}" data-name="${esc(d.name)}">${esc(d.name)}　<small>庫存 ${d.stock.toLocaleString()}</small></div>`).join("");
          $$("div", sug).forEach((o) => o.addEventListener("mousedown", () => {
            row.dataset.drugId = o.dataset.id;
            input.value = o.dataset.name;
            sug.innerHTML = "";
          }));
        } catch (e) { /* 狀態列已顯示 */ }
      }, 250);
    });
    input.addEventListener("blur", () => setTimeout(() => (sug.innerHTML = ""), 150));
    $(".d-del", row).addEventListener("click", () => row.remove());
    // 預填常用藥，方便示範
    const preset = $$(".rx-items").length === 1 ? [4, "Atorvastatin 20mg #4"] : [3, "Amlodipine 5mg #3"];
    row.dataset.drugId = preset[0];
    input.value = preset[1];
  }

  async function submitVisit(btn, q, pid) {
    clearError();
    const [icd, diagnosis] = ICD.find(([c]) => c === $("#v-icd").value);
    const items = $$(".rx-items").filter((r) => r.dataset.drugId).map((r) => ({
      drugId: Number(r.dataset.drugId), qty: Number($(".d-qty", r).value) || 1, days: Number($(".d-days", r).value) || 1,
    }));
    try {
      const r = await busy(btn, () => api("POST", `/his/api/visits/${q.apptId}/complete`,
        { icd10: icd, diagnosis, notes: $("#v-notes").value, items, lab: $("#v-lab").value }, pid));
      $("#v-result").innerHTML = `<div class="success">✔ 看診完成：處方 #${esc(r.prescription.rxId)}（${esc(r.prescription.items)} 項，NT$ ${esc(r.prescription.total)}）
        ${r.labOrder ? `，已開立檢驗「${esc(r.labOrder.testName)}」` : ""}，請病患至藥局領藥。</div>`;
      btn.disabled = true;
      loadQueue();
      refreshStats();
    } catch (e) { showError(e, "完成看診"); }
  }

  function recordsTimeline(list) {
    if (!list.length) return `<div class="empty">無就診紀錄</div>`;
    return `<div class="timeline">${list.map((r) => `<div class="tl"><div class="d">${esc(r.visitDate)}｜${esc(deptName(r.dept))}｜${esc(r.doctor)} 醫師</div>
      <div><strong>${esc(r.icd10)}</strong> ${esc(r.diagnosis)}</div></div>`).join("")}</div>`;
  }
  function labTable(list, err) {
    if (err) return `<div class="empty">檢驗系統暫時無法使用（${esc(err)}）</div>`;
    if (!list || !list.length) return `<div class="empty">無檢驗紀錄</div>`;
    return `<div class="table-wrap"><table><thead><tr><th>日期</th><th>項目</th><th>狀態</th><th>結果</th></tr></thead><tbody>
      ${list.map((l) => `<tr><td>${esc(l.createdAt.slice(0, 10))}</td><td>${esc(l.testName)}</td>
        <td><span class="tag ${l.status === "DONE" ? "done" : "wait"}">${l.status === "DONE" ? "已發報告" : "檢驗中"}</span></td><td>${esc(l.result || "–")}</td></tr>`).join("")}
    </tbody></table></div>`;
  }

  // ---------------------------------------------------------------- 藥局調劑
  let rxStatus = "NEW";
  function initPharmacy() {
    $$(".seg-btn").forEach((b) => b.addEventListener("click", () => {
      $$(".seg-btn").forEach((x) => x.classList.remove("active"));
      b.classList.add("active");
      rxStatus = b.dataset.rx;
      loadRx();
    }));
    $("#rx-refresh").addEventListener("click", loadRx);
    $("#drug-search").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      clearError();
      const q = $("input", ev.currentTarget).value.trim();
      try {
        const r = await busy($("button", ev.currentTarget), () => api("GET", `/pharmacy/api/drugs?q=${encodeURIComponent(q)}`));
        $("#drug-results").innerHTML = r.drugs.length ? `<div class="table-wrap"><table><thead><tr><th>代碼</th><th>藥名</th><th>庫存</th><th>單價</th></tr></thead><tbody>
          ${r.drugs.map((d) => `<tr><td>${esc(d.code)}</td><td>${esc(d.name)}</td><td>${d.stock.toLocaleString()}</td><td>${esc(d.unitPrice)}</td></tr>`).join("")}
          </tbody></table></div>` : `<div class="empty">查無藥品</div>`;
      } catch (e) { showError(e, "查詢藥品"); }
    });
  }
  async function loadRx() {
    try {
      const r = await busy($("#rx-refresh"), () => api("GET", `/pharmacy/api/prescriptions?status=${rxStatus}&limit=20`));
      const box = $("#rx-list");
      if (!r.prescriptions.length) { box.innerHTML = `<div class="empty">${rxStatus === "NEW" ? "目前沒有待調劑處方" : "尚無領藥紀錄"}</div>`; return; }
      box.innerHTML = r.prescriptions.map((p) => `<div class="rx">
        <div><strong>處方 #${p.rxId}</strong>　${esc(p.name)}（${esc(p.chartNo)}）
          <div class="meta">${esc(p.createdAt.slice(0, 19))}｜NT$ ${esc(p.total)}</div>
          <ul>${p.items.map((i) => `<li>${esc(i.drug)}　每次 ${i.qty}，${i.days} 天</li>`).join("")}</ul></div>
        <div>${rxStatus === "NEW" ? `<button class="btn primary" data-rx="${p.rxId}" data-pid="${p.patientId}">調劑完成</button>` : '<span class="tag done">已領藥</span>'}</div>
      </div>`).join("");
      $$("button[data-rx]", box).forEach((b) => b.addEventListener("click", async () => {
        clearError();
        try {
          await busy(b, () => api("POST", `/pharmacy/api/prescriptions/${b.dataset.rx}/dispense`, {}, b.dataset.pid));
          b.closest(".rx").remove();
          refreshStats();
        } catch (e) { showError(e, "調劑"); }
      }));
    } catch (e) { showError(e, "載入處方"); }
  }

  // ---------------------------------------------------------------- 病歷查詢
  function initRecords() {
    bindSearch("records", async (p) => {
      clearError();
      const box = $("#rec-detail");
      box.innerHTML = `<div class="empty">載入病歷中…</div>`;
      try {
        const rec = await api("GET", `/his/api/patients/${p.patientId}/records`, undefined, p.patientId);
        box.innerHTML = `<h2>${esc(p.name)} 的病歷</h2>${patientCard(p)}
          <h3>就診紀錄（最近 20 筆）</h3>${recordsTimeline(rec.records)}
          <h3>檢驗報告</h3>${labTable(rec.lab ? rec.lab.orders : null, rec.labError)}`;
      } catch (e) {
        box.innerHTML = `<div class="empty">無法載入病歷</div>`;
        showError(e, "查詢病歷");
      }
    });
  }

  // ---------------------------------------------------------------- 頁籤 / 啟動
  function initTabs() {
    $$(".tab").forEach((t) => t.addEventListener("click", () => {
      $$(".tab").forEach((x) => x.classList.remove("active"));
      $$(".panel").forEach((x) => x.classList.remove("active"));
      t.classList.add("active");
      $("#tab-" + t.dataset.tab).classList.add("active");
      clearError();
      if (t.dataset.tab === "doctor" && !$("#doc-doctor").options.length) loadDoctors();
      if (t.dataset.tab === "pharmacy") loadRx();
      try { localStorage.setItem("his.tab", t.dataset.tab); } catch (e) { /* ignore */ }
    }));
  }

  async function start() {
    tickClock();
    setInterval(tickClock, 1000);
    initTabs();
    try {
      departments = (await api("GET", "/his/api/departments")).departments;
    } catch (e) {
      showError(e, "載入科別");
    }
    initRegister();
    initDoctor();
    initPharmacy();
    initRecords();
    refreshStats();
    setInterval(refreshStats, 15000);
    let last = null;
    try { last = localStorage.getItem("his.tab"); } catch (e) { /* ignore */ }
    if (last && last !== "register") { const t = $(`.tab[data-tab="${last}"]`); if (t) t.click(); }
  }
  start();
})();
