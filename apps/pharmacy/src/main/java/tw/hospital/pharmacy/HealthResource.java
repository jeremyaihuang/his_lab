package tw.hospital.pharmacy;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.JsonUtil;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @GET
    public Response health() {
        return JsonUtil.ok(JsonUtil.obj().add("status", "UP").add("app", "pharmacy").build());
    }
}
