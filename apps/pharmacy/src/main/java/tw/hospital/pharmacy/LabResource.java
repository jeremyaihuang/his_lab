package tw.hospital.pharmacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.JsonUtil;

/** 檢驗單 / 檢驗報告 */
@Path("/lab")
@Produces(MediaType.APPLICATION_JSON)
public class LabResource {

    @GET
    @Path("/patients/{id}")
    public Response results(@PathParam("id") int id) throws SQLException {
        CommonScenarios.apply();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT ORDER_ID, TEST_CODE, TEST_NAME, STATUS, RESULT, CREATED_AT FROM HIS.LAB_ORDERS "
               + "WHERE PATIENT_ID = ? ORDER BY CREATED_AT DESC FETCH FIRST 10 ROWS ONLY")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(JsonUtil.obj().add("orderId", rs.getLong(1)).add("testCode", rs.getString(2))
                        .add("testName", rs.getString(3)).add("status", rs.getString(4))
                        .add("result", rs.getString(5) == null ? "" : rs.getString(5)).add("createdAt", rs.getTimestamp(6).toString()));
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("patientId", id).add("orders", arr).build());
    }

    @POST
    @Path("/orders")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response order(String body) throws SQLException {
        CommonScenarios.apply();
        JsonObject req = JsonUtil.parse(body);
        String code = req.getString("testCode", "CBC");
        String name;
        switch (code) {
            case "HBA1C": name = "糖化血色素"; break;
            case "LIPID": name = "血脂肪"; break;
            case "ALT": name = "肝功能 ALT"; break;
            case "CRE": name = "腎功能 Creatinine"; break;
            default: code = "CBC"; name = "全血球計數";
        }
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO HIS.LAB_ORDERS (PATIENT_ID, TEST_CODE, TEST_NAME, STATUS) VALUES (?, ?, ?, 'ORDERED')",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, req.getInt("patientId"));
            ps.setString(2, code); ps.setString(3, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return JsonUtil.status(201, JsonUtil.obj().add("orderId", rs.getLong(1)).add("testCode", code).add("testName", name).build());
            }
        }
    }
}
