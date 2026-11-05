package tw.hospital.common;

/** 兩個 WAS 都有的情境 */
public final class CommonScenarios {
    private CommonScenarios() {}

    public static void register() {
        Chaos.register("mem-leak", "記憶體洩漏：每個請求累積 N KB（預設 512）不釋放，Heap 持續成長、GC 變頻繁，最後 OutOfMemoryError", new Chaos.Hook() {
            public void enable(String v) { }
            public void disable() { Chaos.releaseMemory(); System.gc(); }
        });
        Chaos.register("conn-leak", "JDBC 連線洩漏：每個請求多借一條連線不歸還，Connection Pool 很快耗盡，請求卡在等連線", new Chaos.Hook() {
            public void enable(String v) { }
            public void disable() { Db.releaseLeaked(); }
        });
        Chaos.register("cpu-burn", "CPU 飆高：每個請求額外做 N 毫秒（預設 300）的無意義運算", null);
    }

    /** 每個 API 進入點呼叫，套用共用情境 */
    public static void apply() {
        Chaos.leakMemory();
        if (Chaos.on("cpu-burn")) {
            long end = System.nanoTime() + Chaos.intValue("cpu-burn", 300) * 1_000_000L;
            double x = 0;
            while (System.nanoTime() < end) x += Math.sqrt(x + 1.0);
            if (x < 0) System.out.println(x);
        }
    }
}
