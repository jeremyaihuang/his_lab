package tw.hospital.pharmacy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.Chaos;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;
import tw.hospital.common.JsonUtil;

/** 開立處方：一個交易內建立處方、扣藥品庫存 */
@Path("/prescriptions")
@Produces(MediaType.APPLICATION_JSON)
public class PrescriptionResource {

    private static final class Item {
        final int drugId, qty, days;
        Item(int d, int q, int ds) { drugId = d; qty = q; days = ds; }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(String body) throws Exception {
        CommonScenarios.apply();
        JsonObject req = JsonUtil.parse(body);
        List<Item> items = new ArrayList<>();
        JsonArray arr = req.containsKey("items") ? req.getJsonArray("items") : null;
        if (arr != null) for (int i = 0; i < arr.size(); i++) {
            JsonObject o = arr.getJsonObject(i);
            items.add(new Item(o.getInt("drugId"), o.getInt("qty", 1), o.getInt("days", 3)));
        }
        if (items.isEmpty()) items.add(new Item(1, 1, 3));

        boolean deadlock = Chaos.on("deadlock");
        if (deadlock) {
            // Bug：依請求不同，有時遞增有時遞減的順序更新庫存 → 兩筆交易互鎖
            items.sort((a, b) -> Integer.compare(a.drugId, b.drugId));
            if ((System.nanoTime() & 1) == 1) Collections.reverse(items);
        }

        try (Connection c = Db.get()) {
            c.setAutoCommit(false);
            try {
                long rxId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO HIS.PRESCRIPTIONS (PATIENT_ID, DOCTOR_ID, APPT_ID) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, req.getInt("patientId"));
                    ps.setInt(2, req.getInt("doctorId", 1));
                    ps.setLong(3, req.containsKey("apptId") ? req.getJsonNumber("apptId").longValue() : 0L);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); rxId = rs.getLong(1); }
                }
                BigDecimal total = BigDecimal.ZERO;
                for (Item it : items) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE HIS.DRUGS SET STOCK = STOCK - ? WHERE DRUG_ID = ? AND STOCK >= ?")) {
                        ps.setInt(1, it.qty * it.days); ps.setInt(2, it.drugId); ps.setInt(3, it.qty * it.days);
                        if (ps.executeUpdate() == 0) throw new IllegalStateException("藥品 " + it.drugId + " 庫存不足");
                    }
                    if (deadlock) Thread.sleep(300);
                    try (PreparedStatement ps = c.prepareStatement("SELECT UNIT_PRICE FROM HIS.DRUGS WHERE DRUG_ID = ?")) {
                        ps.setInt(1, it.drugId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) total = total.add(rs.getBigDecimal(1).multiply(BigDecimal.valueOf((long) it.qty * it.days)));
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO HIS.PRESCRIPTION_ITEMS (RX_ID, DRUG_ID, QTY, DAYS) VALUES (?, ?, ?, ?)")) {
                        ps.setLong(1, rxId); ps.setInt(2, it.drugId); ps.setInt(3, it.qty); ps.setInt(4, it.days);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE HIS.PRESCRIPTIONS SET TOTAL = ? WHERE RX_ID = ?")) {
                    ps.setBigDecimal(1, total); ps.setLong(2, rxId);
                    ps.executeUpdate();
                }
                c.commit();
                return JsonUtil.status(201, JsonUtil.obj().add("rxId", rxId).add("items", items.size()).add("total", total).build());
            } catch (SQLException | RuntimeException | InterruptedException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** 藥局待調劑 / 已調劑清單（含病患姓名與藥品明細） */
    @GET
    public Response list(@QueryParam("status") String status, @QueryParam("limit") int limit) throws SQLException {
        CommonScenarios.apply();
        String st = status == null || status.isEmpty() ? "NEW" : status;
        int n = limit <= 0 || limit > 50 ? 20 : limit;
        JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection c = Db.get()) {
            // 先把處方讀完再查明細（autocommit 下執行下一個查詢會關閉前一個 cursor）
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                     "SELECT R.RX_ID, R.PATIENT_ID, P.NAME, P.CHART_NO, R.TOTAL, R.CREATED_AT, R.STATUS "
                   + "FROM HIS.PRESCRIPTIONS R JOIN HIS.PATIENTS P ON P.PATIENT_ID = R.PATIENT_ID "
                   + "WHERE R.STATUS = ? ORDER BY R.RX_ID " + ("NEW".equals(st) ? "ASC" : "DESC") + " FETCH FIRST " + n + " ROWS ONLY")) {
                ps.setString(1, st);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            rs.getBigDecimal(5), rs.getTimestamp(6).toString(), rs.getString(7)});
                }
            }
            try (PreparedStatement items = c.prepareStatement(
                     "SELECT D.NAME, I.QTY, I.DAYS FROM HIS.PRESCRIPTION_ITEMS I JOIN HIS.DRUGS D ON D.DRUG_ID = I.DRUG_ID WHERE I.RX_ID = ?")) {
                for (Object[] r : rows) {
                    JsonArrayBuilder it = Json.createArrayBuilder();
                    items.setLong(1, (Long) r[0]);
                    try (ResultSet ri = items.executeQuery()) {
                        while (ri.next()) it.add(JsonUtil.obj().add("drug", ri.getString(1)).add("qty", ri.getInt(2)).add("days", ri.getInt(3)));
                    }
                    JsonObjectBuilder o = JsonUtil.obj().add("rxId", (Long) r[0]).add("patientId", (Integer) r[1])
                            .add("name", (String) r[2]).add("chartNo", (String) r[3])
                            .add("createdAt", (String) r[5]).add("status", (String) r[6]).add("items", it);
                    if (r[4] != null) o.add("total", (BigDecimal) r[4]); else o.add("total", 0);
                    arr.add(o);
                }
            }
        }
        return JsonUtil.ok(JsonUtil.obj().add("status", st).add("prescriptions", arr).build());
    }

    /** 藥師調劑完成、病患領藥 */
    @POST
    @Path("/{rxId}/dispense")
    public Response dispense(@PathParam("rxId") long rxId) throws SQLException {
        CommonScenarios.apply();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("UPDATE HIS.PRESCRIPTIONS SET STATUS = 'DISPENSED' WHERE RX_ID = ? AND STATUS = 'NEW'")) {
            ps.setLong(1, rxId);
            if (ps.executeUpdate() == 0) return JsonUtil.error(409, "處方 " + rxId + " 不存在或已調劑");
        }
        return JsonUtil.ok(JsonUtil.obj().add("rxId", rxId).add("status", "DISPENSED").build());
    }
}
