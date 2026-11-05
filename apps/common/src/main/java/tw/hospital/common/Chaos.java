package tw.hospital.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 錯誤情境開關（每個 JVM 各自一份，由 /api/chaos 控制）。
 * value 為字串，例如 slow-downstream 的值是延遲秒數。
 */
public final class Chaos {
    private static final Logger LOG = Logger.getLogger("tw.hospital.chaos");
    private static final Map<String, String> FLAGS = new ConcurrentHashMap<>();
    private static final Map<String, Hook> HOOKS = new ConcurrentHashMap<>();
    private static final Map<String, String> DESCRIPTIONS = new ConcurrentHashMap<>();
    /** mem-leak 情境累積的記憶體（不會被 GC） */
    private static final List<byte[]> LEAK = new ArrayList<>();

    public interface Hook {
        void enable(String value) throws Exception;
        void disable() throws Exception;
    }

    private Chaos() {}

    public static void register(String name, String description, Hook hook) {
        DESCRIPTIONS.put(name, description);
        if (hook != null) HOOKS.put(name, hook);
    }

    public static boolean on(String name) { return FLAGS.containsKey(name); }

    public static int intValue(String name, int def) {
        String v = FLAGS.get(name);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public static synchronized void enable(String name, String value) throws Exception {
        if (!DESCRIPTIONS.containsKey(name)) throw new IllegalArgumentException("unknown scenario: " + name);
        Hook h = HOOKS.get(name);
        if (h != null) h.enable(value);
        FLAGS.put(name, value == null ? "" : value);
        LOG.warning("CHAOS ENABLED " + name + " value=" + value);
    }

    public static synchronized void disable(String name) throws Exception {
        Hook h = HOOKS.get(name);
        FLAGS.remove(name);
        if (h != null) h.disable();
        LOG.warning("CHAOS DISABLED " + name);
    }

    public static synchronized void disableAll() {
        for (String n : new ArrayList<>(FLAGS.keySet())) {
            try { disable(n); } catch (Exception e) { LOG.warning("disable " + n + " failed: " + e); }
        }
    }

    public static Map<String, String> status() {
        Map<String, String> m = new TreeMap<>();
        for (String n : DESCRIPTIONS.keySet()) m.put(n, FLAGS.containsKey(n) ? "ON(" + FLAGS.get(n) + ")" : "off");
        return m;
    }

    public static Map<String, String> descriptions() { return new TreeMap<>(DESCRIPTIONS); }

    /** 每次呼叫累積 kb KB 的記憶體，模擬快取沒有上限 / 物件沒釋放 */
    public static void leakMemory() {
        if (!on("mem-leak")) return;
        int kb = intValue("mem-leak", 512);
        synchronized (LEAK) { LEAK.add(new byte[kb * 1024]); }
    }

    public static void releaseMemory() { synchronized (LEAK) { LEAK.clear(); } }

    public static int leakedMb() { synchronized (LEAK) { long s = 0; for (byte[] b : LEAK) s += b.length; return (int) (s / 1024 / 1024); } }
}
