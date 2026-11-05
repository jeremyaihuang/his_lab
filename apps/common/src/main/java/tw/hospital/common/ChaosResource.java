package tw.hospital.common;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/** 錯誤情境控制：GET /api/chaos、PUT /api/chaos/{name}?value=、DELETE /api/chaos/{name}、DELETE /api/chaos */
@Path("/chaos")
@Produces(MediaType.APPLICATION_JSON)
public class ChaosResource {

    @GET
    public Response status() {
        return JsonUtil.ok(JsonUtil.obj()
                .add("scenarios", JsonUtil.map(Chaos.status()))
                .add("descriptions", JsonUtil.map(Chaos.descriptions()))
                .add("leakedConnections", Db.leakedCount())
                .add("leakedMemoryMb", Chaos.leakedMb())
                .build());
    }

    @PUT
    @Path("/{name}")
    public Response enable(@PathParam("name") String name, @QueryParam("value") String value) {
        try {
            Chaos.enable(name, value);
            return status();
        } catch (IllegalArgumentException e) {
            return JsonUtil.error(404, e.getMessage());
        } catch (Exception e) {
            return JsonUtil.error(500, name + ": " + e);
        }
    }

    @DELETE
    @Path("/{name}")
    public Response disable(@PathParam("name") String name) {
        try {
            Chaos.disable(name);
            return status();
        } catch (Exception e) {
            return JsonUtil.error(500, name + ": " + e);
        }
    }

    @DELETE
    public Response reset() {
        Chaos.disableAll();
        return status();
    }
}
