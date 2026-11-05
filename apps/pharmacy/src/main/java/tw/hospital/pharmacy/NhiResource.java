package tw.hospital.pharmacy;

import java.util.concurrent.ThreadLocalRandom;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import tw.hospital.common.Chaos;
import tw.hospital.common.JsonUtil;

/** 模擬健保署 VPN 閘道：健保卡驗證（正常 30~120ms） */
@Path("/nhi")
@Produces(MediaType.APPLICATION_JSON)
public class NhiResource {
    @GET
    @Path("/verify")
    public Response verify(@QueryParam("card") String card) throws InterruptedException {
        if (Chaos.on("nhi-down")) return JsonUtil.error(503, "健保署 VPN 閘道無回應 (NHI-503)");
        long delay = 30 + ThreadLocalRandom.current().nextInt(90);
        if (Chaos.on("slow-downstream")) delay = Chaos.intValue("slow-downstream", 25) * 1000L;
        Thread.sleep(delay);
        if (card == null || card.length() != 12) return JsonUtil.error(400, "invalid NHI card");
        return JsonUtil.ok(JsonUtil.obj().add("card", card).add("valid", true).add("copay", 50).build());
    }
}
