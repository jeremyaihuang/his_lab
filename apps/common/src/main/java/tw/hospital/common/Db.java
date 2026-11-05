package tw.hospital.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/** 取得 WAS 管理的 DB2 DataSource（jdbc/HISDB，Connection Pool 由 WAS 管理） */
public final class Db {
    private static final Logger LOG = Logger.getLogger("tw.hospital.db");
    private static volatile DataSource ds;
    /** conn-leak 情境：借出去沒有還的連線 */
    private static final List<Connection> LEAKED = new ArrayList<>();

    private Db() {}

    public static DataSource dataSource() {
        if (ds == null) {
            synchronized (Db.class) {
                if (ds == null) {
                    try { ds = (DataSource) new InitialContext().lookup("jdbc/HISDB"); }
                    catch (NamingException e) { throw new IllegalStateException("jdbc/HISDB not found", e); }
                }
            }
        }
        return ds;
    }

    public static Connection get() throws SQLException {
        if (Chaos.on("conn-leak")) {
            // 模擬程式在例外路徑忘了 close()：每次請求額外借一條連線且不歸還
            Connection leaked = dataSource().getConnection();
            synchronized (LEAKED) { LEAKED.add(leaked); }
        }
        return dataSource().getConnection();
    }

    public static void releaseLeaked() {
        synchronized (LEAKED) {
            for (Connection c : LEAKED) { try { c.close(); } catch (SQLException e) { /* ignore */ } }
            LOG.info("released " + LEAKED.size() + " leaked connections");
            LEAKED.clear();
        }
    }

    public static int leakedCount() { synchronized (LEAKED) { return LEAKED.size(); } }
}
