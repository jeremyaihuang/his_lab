package tw.hospital.his;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import tw.hospital.common.Chaos;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;

@WebListener
public class HisStartup implements ServletContextListener {
    /** slow-sql 開啟前的 Buffer Pool 頁數，關閉時還原 */
    private static volatile long originalBufferPoolPages = 398814;

    static String pharmacyUrl() {
        String u = System.getenv("PHARMACY_URL");
        return u == null || u.isEmpty() ? "http://hospital-was-pharmacy:9080/pharmacy" : u;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        CommonScenarios.register();
        Chaos.register("slow-sql", "病歷查詢變慢：DROP 病歷表的 IX_MR_PATIENT 索引 + Buffer Pool 被調小（1000 頁），查病歷變成 60 萬筆全表掃描且大量實體讀取（關閉時重建索引、還原 Buffer Pool）", new Chaos.Hook() {
            public void enable(String v) throws Exception {
                try (Connection c = Db.dataSource().getConnection(); Statement s = c.createStatement()) {
                    try (ResultSet rs = s.executeQuery(
                            "SELECT BP_CUR_BUFFSZ FROM TABLE(MON_GET_BUFFERPOOL('IBMDEFAULTBP', -2)) AS T")) {
                        if (rs.next() && rs.getLong(1) > 1000) originalBufferPoolPages = rs.getLong(1);
                    }
                    try { s.execute("DROP INDEX HIS.IX_MR_PATIENT"); }
                    catch (SQLException e) { if (e.getErrorCode() != -204) throw e; }   // 已經刪過
                    s.execute("ALTER BUFFERPOOL IBMDEFAULTBP IMMEDIATE SIZE 1000");
                }
            }
            public void disable() throws Exception {
                try (Connection c = Db.dataSource().getConnection(); Statement s = c.createStatement()) {
                    try { s.execute("CREATE INDEX HIS.IX_MR_PATIENT ON HIS.MEDICAL_RECORDS (PATIENT_ID, VISIT_DATE)"); }
                    catch (SQLException e) { if (e.getErrorCode() != -601) throw e; }   // 索引已存在
                    s.execute("CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE HIS.MEDICAL_RECORDS AND INDEXES ALL')");
                    s.execute("ALTER BUFFERPOOL IBMDEFAULTBP IMMEDIATE SIZE " + originalBufferPoolPages + " AUTOMATIC");
                }
            }
        });
        Chaos.register("npe-bug", "掛號程式 Bug：沒有健保卡號的病患掛號時發生 NullPointerException（HTTP 500）", null);
        Chaos.register("register-error", "掛號失敗：N% 的掛號請求回傳 HTTP 500（預設 30）", null);
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Chaos.disableAll();
    }
}
