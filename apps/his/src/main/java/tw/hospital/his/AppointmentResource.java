package tw.hospital.his;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.Chaos;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.Http;
import tw.hospital.common.JsonUtil;

/** 門診掛號 */
@Path("/appointments")
@Produces(MediaType.APPLICATION_JSON)
public class AppointmentResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(String body) throws Exception {
        CommonScenarios.apply();
        JsonObject req = JsonUtil.parse(body);
        int patientId = req.getInt("patientId");
        String dept = req.getString("deptCode", "FM01");

        if (Chaos.on("register-error") && ThreadLocalRandom.current().nextInt(100) < Chaos.intValue("register-error", 30)) {
            throw new IllegalStateException("掛號序號產生失敗：序號服務暫時無法使用 (REG-5001)");
        }

        try (Connection c = Db.get()) {
            String nhiCard;
            try (PreparedStatement ps = c.prepareStatement("SELECT NHI_CARD FROM HIS.PATIENTS WHERE PATIENT_ID = ?")) {
                ps.setInt(1, patientId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return JsonUtil.error(404, "patient " + patientId + " not found");
                    nhiCard = rs.getString(1);
                }
            }

            // 健保卡驗證（呼叫藥局 WAS 上的健保閘道）
            boolean selfPay = false;
            if (Chaos.on("npe-bug")) {
                nhiCard = nhiCard.trim();   // Bug：沒有健保卡號的病患 nhiCard 為 null → NullPointerException
            }
            if (nhiCard == null) {
                selfPay = true;
            } else {
                Http.Result r = Http.call("GET", HisStartup.pharmacyUrl() + "/api/nhi/verify?card=" + nhiCard, null,
                        Collections.singletonMap("X-Patient-Id", String.valueOf(patientId)), 60000);
                if (!r.ok()) return JsonUtil.error(502, "健保卡驗證失敗：NHI gateway HTTP " + r.status);
            }

            int doctorId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DOCTOR_ID FROM HIS.DOCTORS WHERE DEPT_CODE = ? ORDER BY RAND() FETCH FIRST 1 ROW ONLY")) {
                ps.setString(1, dept);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return JsonUtil.error(400, "unknown department " + dept);
                    doctorId = rs.getInt(1);
                }
            }
            int seq;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(SEQ_NO), 0) + 1 FROM HIS.APPOINTMENTS WHERE DOCTOR_ID = ? AND VISIT_DATE = CURRENT DATE")) {
                ps.setInt(1, doctorId);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); seq = rs.getInt(1); }
            }
            long apptId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO HIS.APPOINTMENTS (PATIENT_ID, DOCTOR_ID, DEPT_CODE, VISIT_DATE, SEQ_NO) VALUES (?, ?, ?, CURRENT DATE, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, patientId); ps.setInt(2, doctorId); ps.setString(3, dept); ps.setInt(4, seq);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); apptId = rs.getLong(1); }
            }
            return JsonUtil.status(201, JsonUtil.obj().add("apptId", apptId).add("patientId", patientId)
                    .add("doctorId", doctorId).add("deptCode", dept).add("seqNo", seq).add("selfPay", selfPay).build());
        }
    }

    /** 醫師今日看診清單 */
    @GET
    @Path("/queue")
    public Response queue(@QueryParam("doctorId") int doctorId) throws SQLException {
        CommonScenarios.apply();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT A.APPT_ID, A.SEQ_NO, A.STATUS, P.NAME, P.CHART_NO FROM HIS.APPOINTMENTS A "
               + "JOIN HIS.PATIENTS P ON P.PATIENT_ID = A.PATIENT_ID "
               + "WHERE A.DOCTOR_ID = ? AND A.VISIT_DATE = ? ORDER BY A.SEQ_NO FETCH FIRST 100 ROWS ONLY")) {
            ps.setInt(1, doctorId);
            ps.setDate(2, new Date(System.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(JsonUtil.obj().add("apptId", rs.getLong(1)).add("seqNo", rs.getInt(2))
                        .add("status", rs.getString(3)).add("name", rs.getString(4)).add("chartNo", rs.getString(5)));
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("doctorId", doctorId).add("queue", arr).build());
    }
}
