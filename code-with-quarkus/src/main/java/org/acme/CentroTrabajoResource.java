package org.acme;

import java.util.List;
import jakarta.annotation.security.RolesAllowed;
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

@Path("/api/centros-trabajo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("SUPERADMIN")
public class CentroTrabajoResource {

    @GET
    public Response listar() {
        List<CentroTrabajo> lista = CentroTrabajo.listAll();
        return Response.ok(lista).build();
    }

    @POST
    @Transactional
    public Response crear(CentroTrabajo ct) {
        if (ct == null || ct.claveCT == null || ct.claveCT.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"La Clave CCT es obligatoria.\"}").build();
        }

        String claveLimpia = ct.claveCT.trim().toUpperCase();

        CentroTrabajo repetido = CentroTrabajo.find("upper(claveCT) = ?1", claveLimpia).firstResult();
        if (repetido != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"La Clave CCT ya se encuentra registrada.\"}").build();
        }

        ct.id = null;
        ct.claveCT = claveLimpia;
        ct.estatus = (ct.estatus != null && !ct.estatus.isBlank()) ? ct.estatus.trim().toUpperCase() : "A";
        ct.persist();
        sincronizarConEmpleado(ct);
        return Response.status(Response.Status.CREATED).entity(ct).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response actualizar(@PathParam("id") Long id, CentroTrabajo datos) {
        CentroTrabajo existente = CentroTrabajo.findById(id);
        if (existente == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        existente.claveCT = datos.claveCT != null ? datos.claveCT.trim().toUpperCase() : existente.claveCT;
        existente.nombreCT = datos.nombreCT;
        existente.unidadResp = datos.unidadResp;
        existente.subDireccion = datos.subDireccion;
        existente.departamento = datos.departamento;
        existente.director = datos.director;
        existente.tipoVial = datos.tipoVial;
        existente.nombreVial = datos.nombreVial;
        existente.numExt = datos.numExt;
        existente.numInt = datos.numInt;
        existente.tipoAsent = datos.tipoAsent;
        existente.nomAsent = datos.nomAsent;
        existente.codPostal = datos.codPostal;
        existente.municipio = datos.municipio;
        existente.localidad = datos.localidad;
        existente.vialidIzq = datos.vialidIzq;
        existente.vialidDer = datos.vialidDer;
        existente.vialidPost = datos.vialidPost;
        existente.refVialid = datos.refVialid;
        existente.zonaEscolar = datos.zonaEscolar;
        existente.sector = datos.sector;
        existente.turno = datos.turno;
        existente.telefono = datos.telefono;
        existente.estatus = (datos.estatus != null && !datos.estatus.isBlank()) ? datos.estatus.trim().toUpperCase() : "A";
        existente.persist();

        sincronizarConEmpleado(existente);

        return Response.ok(existente).build();
    }

    private void sincronizarConEmpleado(CentroTrabajo ct) {
        if (ct.claveCT == null || ct.claveCT.isEmpty()) return;
        List<Empleado> empleados = Empleado.find("upper(cct) = ?1", ct.claveCT.toUpperCase()).list();
        for (Empleado emp : empleados) {
            if (ct.nombreCT != null && !ct.nombreCT.isEmpty()) {
                emp.nombreEscuela = ct.nombreCT.toUpperCase();
            }
            emp.persist();
        }
    }
}