package tw.hospital.common;

import java.io.StringReader;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/** JSON-P 小工具（Java EE 7 內建，不需要額外的 JSON 函式庫） */
public final class JsonUtil {
    private JsonUtil() {}

    public static JsonObject parse(String s) {
        if (s == null || s.trim().isEmpty()) return Json.createObjectBuilder().build();
        try (JsonReader r = Json.createReader(new StringReader(s))) { return r.readObject(); }
    }

    public static JsonObjectBuilder obj() { return Json.createObjectBuilder(); }

    public static JsonObject map(Map<String, String> m) {
        JsonObjectBuilder b = obj();
        for (Map.Entry<String, String> e : m.entrySet()) b.add(e.getKey(), e.getValue());
        return b.build();
    }

    public static Response ok(JsonObject o) {
        return Response.ok(o.toString(), MediaType.APPLICATION_JSON + ";charset=UTF-8").build();
    }

    public static Response status(int code, JsonObject o) {
        return Response.status(code).entity(o.toString()).type(MediaType.APPLICATION_JSON + ";charset=UTF-8").build();
    }

    public static Response error(int code, String message) {
        return status(code, obj().add("error", message).build());
    }
}
