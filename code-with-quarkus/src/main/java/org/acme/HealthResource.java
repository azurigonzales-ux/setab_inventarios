package org.acme;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/health")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class HealthResource {

    @GET
    public Response verificarSalud() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("servicio", "SETAB-Backend");
        payload.put("timestamp", Instant.now().toString());
        return Response.ok(payload).build();
    }
}
