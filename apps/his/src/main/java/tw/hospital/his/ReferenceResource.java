package tw.hospital.his;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.JsonUtil;

/** 網頁用的基本資料與今日統計：科別、醫師、今日門診概況 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ReferenceResource {

    @GET
    @Path("departments")
    public Response departments() throws SQLException {
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DEPT_CODE, DEPT_NAME FROM HIS.DEPARTMENTS ORDER BY DEPT_CODE")) {
            while (rs.next()) arr.add(JsonUtil.obj().add("code", rs.getString(1).trim()).add("name", rs.getString(2)));
        }
        return JsonUtil.ok(JsonUtil.obj().add("departments", arr).build());
    }

    @GET
    @Path("doctors")
    public Response doctors(@QueryParam("dept") String dept) throws SQLException {
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT D.DOCTOR_ID, D.NAME, D.DEPT_CODE, "
               + " (SELECT COUNT(*) FROM HIS.APPOINTMENTS A WHERE A.DOCTOR_ID = D.DOCTOR_ID AND A.VISIT_DATE = CURRENT DATE AND A.STATUS = 'BOOKED') "
               + "FROM HIS.DOCTORS D" + (dept == null || dept.isEmpty() ? "" : " WHERE D.DEPT_CODE = ?") + " ORDER BY D.DOCTOR_ID")) {
            if (dept != null && !dept.isEmpty()) ps.setString(1, dept);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(JsonUtil.obj().add("doctorId", rs.getInt(1)).add("name", rs.getString(2))
                        .add("deptCode", rs.getString(3).trim()).add("waiting", rs.getInt(4)));
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("doctors", arr).build());
    }

    /** 今日門診概況（首頁看板） */
    @GET
    @Path("stats/today")
    public Response today() throws SQLException {
        CommonScenarios.apply();
        try (Connection c = Db.get(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*), COALESCE(SUM(CASE WHEN STATUS = 'DONE' THEN 1 ELSE 0 END), 0) "
               + "FROM HIS.APPOINTMENTS WHERE VISIT_DATE = CURRENT DATE")) {
            rs.next();
            int total = rs.getInt(1), done = rs.getInt(2);
            JsonArrayBuilder byDept = Json.createArrayBuilder();
            try (ResultSet r2 = c.createStatement().executeQuery(
                    "SELECT D.DEPT_NAME, COUNT(A.APPT_ID) FROM HIS.DEPARTMENTS D LEFT JOIN HIS.APPOINTMENTS A "
                  + "ON A.DEPT_CODE = D.DEPT_CODE AND A.VISIT_DATE = CURRENT DATE GROUP BY D.DEPT_CODE, D.DEPT_NAME ORDER BY D.DEPT_CODE")) {
                while (r2.next()) byDept.add(JsonUtil.obj().add("dept", r2.getString(1)).add("count", r2.getInt(2)));
            }
            return JsonUtil.ok(JsonUtil.obj().add("appointments", total).add("done", done)
                    .add("waiting", total - done).add("byDept", byDept).build());
        }
    }
}
