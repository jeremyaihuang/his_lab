package tw.hospital.his;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Map;
import javax.json.JsonObject;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.Http;
import tw.hospital.common.JsonUtil;

/** 醫師看診完成：寫病歷 → 開處方（藥局 WAS）→ 開檢驗單（藥局 WAS）→ 結束掛號 */
@Path("/visits")
@Produces(MediaType.APPLICATION_JSON)
public class VisitResource {

    @POST
    @Path("/{apptId}/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response complete(@PathParam("apptId") long apptId, String body) throws Exception {
        CommonScenarios.apply();
        JsonObject req = JsonUtil.parse(body);
        int patientId, doctorId;
        try (Connection c = Db.get()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT PATIENT_ID, DOCTOR_ID, STATUS FROM HIS.APPOINTMENTS WHERE APPT_ID = ?")) {
                ps.setLong(1, apptId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return JsonUtil.error(404, "appointment " + apptId + " not found");
                    if ("DONE".equals(rs.getString(3))) return JsonUtil.error(409, "appointment " + apptId + " already completed");
                    patientId = rs.getInt(1); doctorId = rs.getInt(2);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO HIS.MEDICAL_RECORDS (PATIENT_ID, DOCTOR_ID, VISIT_DATE, ICD10, DIAGNOSIS, NOTES) VALUES (?, ?, CURRENT DATE, ?, ?, ?)")) {
                ps.setInt(1, patientId); ps.setInt(2, doctorId);
                ps.setString(3, req.getString("icd10", "R51")); ps.setString(4, req.getString("diagnosis", "頭痛"));
                ps.setString(5, req.getString("notes", "門診看診紀錄"));
                ps.executeUpdate();
            }
        }

        Map<String, String> hdr = Collections.singletonMap("X-Patient-Id", String.valueOf(patientId));
        String rxBody = JsonUtil.obj().add("patientId", patientId).add("doctorId", doctorId).add("apptId", apptId)
                .add("items", req.containsKey("items") ? req.getJsonArray("items") : javax.json.Json.createArrayBuilder().build())
                .build().toString();
        Http.Result rx = Http.call("POST", HisStartup.pharmacyUrl() + "/api/prescriptions", rxBody, hdr, 60000);
        if (!rx.ok()) return JsonUtil.error(502, "開立處方失敗：pharmacy HTTP " + rx.status + " " + rx.body);

        JsonObject lab = null;
        String labTest = req.getString("lab", "");
        if (!labTest.isEmpty()) {
            Http.Result lr = Http.call("POST", HisStartup.pharmacyUrl() + "/api/lab/orders",
                    JsonUtil.obj().add("patientId", patientId).add("testCode", labTest).build().toString(), hdr, 30000);
            if (lr.ok()) lab = JsonUtil.parse(lr.body);
        }

        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("UPDATE HIS.APPOINTMENTS SET STATUS = 'DONE' WHERE APPT_ID = ?")) {
            ps.setLong(1, apptId);
            ps.executeUpdate();
        }
        javax.json.JsonObjectBuilder out = JsonUtil.obj().add("apptId", apptId).add("prescription", JsonUtil.parse(rx.body));
        if (lab != null) out.add("labOrder", lab);
        return JsonUtil.ok(out.build());
    }
}
