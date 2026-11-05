package tw.hospital.pharmacy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import tw.hospital.common.Chaos;
import tw.hospital.common.CommonScenarios;
import tw.hospital.common.Db;

@WebListener
public class PharmacyStartup implements ServletContextListener {
    private static final Logger LOG = Logger.getLogger("tw.hospital.pharmacy");
    private static volatile Thread lockHolder;

    @Override
    public void contextInitialized(ServletContextEvent e) {
        CommonScenarios.register();
        Chaos.register("db-lock", "藥品庫存鎖等待：背景批次（月結盤點）鎖住常用藥品 N 秒（預設 40）不 commit，開處方卡在 Lock Wait，超過 LOCKTIMEOUT(20s) 失敗", new Chaos.Hook() {
            public void enable(String v) { startLockHolder(); }
            public void disable() throws Exception { stopLockHolder(); }
        });
        Chaos.register("deadlock", "Deadlock：開處方時依不同順序更新藥品庫存，兩筆交易互相等待，DB2 回 SQL0911N reason 2", null);
        Chaos.register("slow-downstream", "健保署閘道變慢：健保卡驗證延遲 N 秒（預設 25），門診 WAS 的 WebContainer 執行緒被卡住、Hung Thread", null);
        Chaos.register("nhi-down", "健保署閘道中斷：健保卡驗證回傳 HTTP 503", null);
    }

    private static synchronized void startLockHolder() {
        if (lockHolder != null) return;
        lockHolder = new Thread(() -> {
            // 由 stopLockHolder() 把 lockHolder 清掉並 interrupt 來結束
            while (lockHolder == Thread.currentThread() && !Thread.currentThread().isInterrupted()) {
                int hold = Math.max(5, Chaos.intValue("db-lock", 40));
                try (Connection c = Db.dataSource().getConnection()) {
                    c.setAutoCommit(false);
                    try (Statement s = c.createStatement()) {
                        int n = s.executeUpdate("UPDATE HIS.DRUGS SET STOCK = STOCK WHERE DRUG_ID <= 30");
                        LOG.warning("db-lock: holding row locks on " + n + " drugs for " + hold + "s");
                        Thread.sleep(hold * 1000L);
                    } finally {
                        c.rollback();
                    }
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception ex) {
                    LOG.warning("db-lock holder error: " + ex);
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
            LOG.warning("db-lock holder stopped");
        }, "chaos-db-lock-holder");
        lockHolder.setDaemon(true);
        lockHolder.start();
    }

    private static synchronized void stopLockHolder() throws InterruptedException {
        Thread t = lockHolder;
        lockHolder = null;
        if (t != null) { t.interrupt(); t.join(10000); }
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Chaos.disableAll();
    }
}
