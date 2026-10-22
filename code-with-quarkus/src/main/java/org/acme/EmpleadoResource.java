package org.acme;

import java.util.List;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/empleados")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SUPERADMIN"})
public class EmpleadoResource {

    @Inject
    PostgresRelacionalCrudService relacionalService;

    @GET
    public List<Empleado> listarEmpleados() {
        if (relacionalService != null && relacionalService.puedeOperarResponsables()) {
            try {
                return relacionalService.listarResponsables();
            } catch (Exception ignored) {
            }
        }
        return Empleado.listAll();
    }

    @POST
    @Transactional
    public Response registrarEmpleado(Empleado empleado) {
        if (relacionalService != null && relacionalService.puedeOperarResponsables()) {
            try {
                Empleado creado = relacionalService.crearResponsable(empleado);
                return Response.status(Response.Status.CREATED).entity(creado).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Error al registrar en tabla responsables: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }
        empleado.persist();
        return Response.status(Response.Status.CREATED).entity(empleado).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response actualizarEmpleado(@PathParam("id") Long id, Empleado datos) {
        if (relacionalService != null && relacionalService.puedeOperarResponsables()) {
            try {
                Empleado actualizado = relacionalService.actualizarResponsable(id, datos);
                return Response.ok(actualizado).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Error al actualizar en tabla responsables: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }
        Empleado existente = Empleado.findById(id);
        if (existente == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (datos.nombre != null) existente.nombre = datos.nombre.trim().toUpperCase();
        if (datos.numeroEmpleado != null) existente.numeroEmpleado = datos.numeroEmpleado.trim();
        if (datos.rfc != null) existente.rfc = datos.rfc.trim().toUpperCase();
        if (datos.cct != null) existente.cct = datos.cct.trim().toUpperCase();
        if (datos.nombreEscuela != null) existente.nombreEscuela = datos.nombreEscuela.trim().toUpperCase();
        if (datos.usuario != null) existente.usuario = datos.usuario.trim().toLowerCase();
        if (datos.cargo != null) existente.cargo = datos.cargo.trim();
        if (datos.area != null) existente.area = datos.area.trim();
        if (datos.curp != null) existente.curp = datos.curp.trim().toUpperCase();
        if (datos.claveResponsable != null) existente.claveResponsable = datos.claveResponsable.trim().toUpperCase();
        if (datos.apellidoPaterno != null) existente.apellidoPaterno = datos.apellidoPaterno.trim().toUpperCase();
        if (datos.apellidoMaterno != null) existente.apellidoMaterno = datos.apellidoMaterno.trim().toUpperCase();
        if (datos.activo != null) existente.activo = datos.activo;
        existente.persist();
        return Response.ok(existente).build();
    }
}
