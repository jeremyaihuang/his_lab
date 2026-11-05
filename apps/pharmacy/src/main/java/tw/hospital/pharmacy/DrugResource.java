package tw.hospital.pharmacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.JsonUtil;

/** 藥品查詢（庫存） */
@Path("/drugs")
@Produces(MediaType.APPLICATION_JSON)
public class DrugResource {
    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") int id) throws SQLException {
        CommonScenarios.apply();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("SELECT DRUG_ID, DRUG_CODE, NAME, STOCK, UNIT_PRICE FROM HIS.DRUGS WHERE DRUG_ID = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return JsonUtil.error(404, "drug " + id + " not found");
                return JsonUtil.ok(JsonUtil.obj().add("drugId", rs.getInt(1)).add("code", rs.getString(2))
                        .add("name", rs.getString(3)).add("stock", rs.getInt(4)).add("unitPrice", rs.getBigDecimal(5)).build());
            }
        }
    }

    /** 藥品搜尋（醫師開藥、藥局查庫存） */
    @GET
    public Response search(@QueryParam("q") String q) throws SQLException {
        CommonScenarios.apply();
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT DRUG_ID, DRUG_CODE, NAME, STOCK, UNIT_PRICE FROM HIS.DRUGS "
               + "WHERE UPPER(NAME) LIKE ? OR DRUG_CODE = ? ORDER BY DRUG_ID FETCH FIRST 20 ROWS ONLY")) {
            String k = q == null ? "" : q.trim().toUpperCase();
            ps.setString(1, "%" + k + "%"); ps.setString(2, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) arr.add(JsonUtil.obj().add("drugId", rs.getInt(1)).add("code", rs.getString(2))
                        .add("name", rs.getString(3)).add("stock", rs.getInt(4)).add("unitPrice", rs.getBigDecimal(5)));
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("drugs", arr).build());
    }
}
