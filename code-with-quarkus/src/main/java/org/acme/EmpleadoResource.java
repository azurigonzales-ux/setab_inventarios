package org.acme;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/empleados")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SUPERADMIN"})
public class EmpleadoResource {

    @GET
    public List<Empleado> listarEmpleados() {
        return Empleado.listAll();
    }

    @POST
    @Transactional
    public Response registrarEmpleado(Empleado empleado) {
        empleado.persist();
        return Response.status(Response.Status.CREATED).entity(empleado).build();
    }
}