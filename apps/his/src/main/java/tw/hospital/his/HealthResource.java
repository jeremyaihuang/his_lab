package tw.hospital.his;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.Db;
import tw.hospital.common.JsonUtil;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @GET
    public Response health() {
        return JsonUtil.ok(JsonUtil.obj().add("status", "UP").add("app", "his").build());
    }

    @GET
    @Path("/db")
    public Response db() {
        try (Connection c = Db.dataSource().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM HIS.DEPARTMENTS")) {
            rs.next();
            return JsonUtil.ok(JsonUtil.obj().add("status", "UP").add("departments", rs.getInt(1)).build());
        } catch (Exception e) {
            return JsonUtil.error(503, e.toString());
        }
    }
}
