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

@Path("/api/catalogos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("SUPERADMIN")
public class CatalogoResource {

    @GET
    @Path("/todos")
    public Response listarTodos() {
        List<CatalogoGeneral> items = CatalogoGeneral.find("upper(tipoCatalogo) <> ?1", "SISTEMA_CONFIG").list();
        return Response.ok(items).build();
    }

    @GET
    @Path("/{tipo}")
    public Response listarPorTipo(@PathParam("tipo") String tipo) {
        String tipoLimpio = tipo != null ? tipo.trim().toLowerCase() : "";
        List<CatalogoGeneral> items = CatalogoGeneral.find("lower(tipoCatalogo) = ?1", tipoLimpio).list();
        return Response.ok(items).build();
    }

    @POST
    @Path("/{tipo}")
    @Transactional
    public Response crear(@PathParam("tipo") String tipo, CatalogoGeneral item) {
        item.id = null;
        item.tipoCatalogo = tipo.toLowerCase();
        if (item.descripcion != null && (item.nombre == null || item.nombre.isEmpty())) {
            item.nombre = item.descripcion.trim().toUpperCase();
        }
        if (item.clave != null) {
            item.clave = item.clave.trim().toUpperCase();
        }
        if (item.descripcion != null) {
            item.descripcion = item.descripcion.trim().toUpperCase();
        }
        if (item.rfc != null) item.rfc = item.rfc.trim().toUpperCase();
        if (item.direccion != null) item.direccion = item.direccion.trim().toUpperCase();
        if (item.colonia != null) item.colonia = item.colonia.trim().toUpperCase();
        if (item.ciudad != null) item.ciudad = item.ciudad.trim().toUpperCase();
        if (item.estado != null) item.estado = item.estado.trim().toUpperCase();
        if (item.telefono != null) item.telefono = item.telefono.trim().toUpperCase();
        if (item.giro != null) item.giro = item.giro.trim().toUpperCase();
        if (item.partidaAsociada != null) item.partidaAsociada = item.partidaAsociada.trim().toUpperCase();
        if (item.subclave != null) item.subclave = item.subclave.trim().toUpperCase();
        if (item.cuentaContable != null) item.cuentaContable = item.cuentaContable.trim().toUpperCase();
        item.persist();
        return Response.status(Response.Status.CREATED).entity(item).build();
    }

    @PUT
    @Path("/{tipo}/{id}")
    @Transactional
    public Response actualizar(@PathParam("tipo") String tipo, @PathParam("id") Long id, CatalogoGeneral datos) {
        CatalogoGeneral existente = CatalogoGeneral.findById(id);
        if (existente == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existente.tipoCatalogo = tipo.toLowerCase();
        existente.clave = datos.clave != null ? datos.clave.trim().toUpperCase() : "";
        existente.descripcion = datos.descripcion != null ? datos.descripcion.trim().toUpperCase() : "";
        existente.nombre = existente.descripcion;
        existente.rfc = datos.rfc != null ? datos.rfc.trim().toUpperCase() : null;
        existente.direccion = datos.direccion != null ? datos.direccion.trim().toUpperCase() : null;
        existente.colonia = datos.colonia != null ? datos.colonia.trim().toUpperCase() : null;
        existente.ciudad = datos.ciudad != null ? datos.ciudad.trim().toUpperCase() : null;
        existente.estado = datos.estado != null ? datos.estado.trim().toUpperCase() : null;
        existente.telefono = datos.telefono != null ? datos.telefono.trim().toUpperCase() : null;
        existente.giro = datos.giro != null ? datos.giro.trim().toUpperCase() : null;
        existente.partidaAsociada = datos.partidaAsociada != null ? datos.partidaAsociada.trim().toUpperCase() : null;
        existente.subclave = datos.subclave != null ? datos.subclave.trim().toUpperCase() : null;
        existente.cuentaContable = datos.cuentaContable != null ? datos.cuentaContable.trim().toUpperCase() : null;
        existente.persist();
        return Response.ok(existente).build();
    }
}