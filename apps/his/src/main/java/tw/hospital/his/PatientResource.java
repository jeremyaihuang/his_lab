package tw.hospital.his;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.Http;
import tw.hospital.common.JsonUtil;

/** 病患資料與病歷查詢 */
@Path("/patients")
@Produces(MediaType.APPLICATION_JSON)
public class PatientResource {

    /** 病患搜尋：病歷號（A000000123）、病患編號或姓名 */
    @GET
    @Path("/search")
    public Response search(@javax.ws.rs.QueryParam("q") String q) throws SQLException {
        CommonScenarios.apply();
        String k = q == null ? "" : q.trim();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        if (k.isEmpty()) return JsonUtil.ok(JsonUtil.obj().add("patients", arr).build());
        int id = -1;
        try { id = Integer.parseInt(k); } catch (NumberFormatException e) { /* 不是數字 */ }
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT PATIENT_ID, CHART_NO, NAME, GENDER, BIRTH_DATE, NHI_CARD FROM HIS.PATIENTS "
               + "WHERE PATIENT_ID = ? OR CHART_NO = ? OR NAME LIKE ? ORDER BY PATIENT_ID FETCH FIRST 20 ROWS ONLY")) {
            ps.setInt(1, id); ps.setString(2, k.toUpperCase()); ps.setString(3, k + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(JsonUtil.obj().add("patientId", rs.getInt(1)).add("chartNo", rs.getString(2))
                        .add("name", rs.getString(3)).add("gender", rs.getString(4)).add("birthDate", rs.getDate(5).toString())
                        .add("nhiCard", rs.getString(6) == null ? "" : rs.getString(6)));
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("patients", arr).build());
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") int id) throws SQLException {
        CommonScenarios.apply();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT PATIENT_ID, CHART_NO, NAME, GENDER, BIRTH_DATE, NHI_CARD, PHONE FROM HIS.PATIENTS WHERE PATIENT_ID = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return JsonUtil.error(404, "patient " + id + " not found");
                JsonObjectBuilder b = JsonUtil.obj()
                    .add("patientId", rs.getInt(1)).add("chartNo", rs.getString(2)).add("name", rs.getString(3))
                    .add("gender", rs.getString(4)).add("birthDate", rs.getDate(5).toString())
                    .add("nhiCard", rs.getString(6) == null ? "" : rs.getString(6));
                return JsonUtil.ok(b.build());
            }
        }
    }

    /** 門診病歷：最近 20 筆就診紀錄 + 藥局 WAS 的檢驗報告（跨 WAS 的分散式追蹤） */
    @GET
    @Path("/{id}/records")
    public Response records(@PathParam("id") int id) throws Exception {
        CommonScenarios.apply();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT M.RECORD_ID, M.VISIT_DATE, M.ICD10, M.DIAGNOSIS, D.NAME, D.DEPT_CODE "
               + "FROM HIS.MEDICAL_RECORDS M JOIN HIS.DOCTORS D ON D.DOCTOR_ID = M.DOCTOR_ID "
               + "WHERE M.PATIENT_ID = ? ORDER BY M.VISIT_DATE DESC FETCH FIRST 20 ROWS ONLY")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arr.add(JsonUtil.obj().add("recordId", rs.getLong(1)).add("visitDate", rs.getDate(2).toString())
                        .add("icd10", rs.getString(3)).add("diagnosis", rs.getString(4))
                        .add("doctor", rs.getString(5)).add("dept", rs.getString(6)));
                }
            }
        }
        Http.Result lab = Http.call("GET", HisStartup.pharmacyUrl() + "/api/lab/patients/" + id, null,
                Collections.singletonMap("X-Patient-Id", String.valueOf(id)), 30000);
        JsonObjectBuilder out = JsonUtil.obj().add("patientId", id).add("records", arr);
        if (lab.ok()) out.add("lab", JsonUtil.parse(lab.body));
        else out.add("labError", "pharmacy returned HTTP " + lab.status);
        return JsonUtil.ok(out.build());
    }
}
