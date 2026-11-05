package tw.hospital.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 服務間 HTTP 呼叫（HttpURLConnection，Instana 會自動串接 trace） */
public final class Http {
    private Http() {}

    public static final class Result {
        public final int status;
        public final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
        public boolean ok() { return status >= 200 && status < 300; }
    }

    public static Result call(String method, String url, String json, Map<String, String> headers, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
            if (h.getValue() != null) c.setRequestProperty(h.getKey(), h.getValue());
        }
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = c.getResponseCode();
        InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (is != null) {
            try (InputStream in = is) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return new Result(status, body);
    }
}
