package org.acme;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/catalogos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CatalogoResource {

    // Extrae solo los registros de un tipo específico
    @GET
    @Path("/{tipo}")
    public List<CatalogoGeneral> obtenerPorTipo(@PathParam("tipo") String tipo) {
        return CatalogoGeneral.list("tipoCatalogo", tipo);
    }

    // Guarda un nuevo elemento en cualquier catálogo
    @POST
    @Transactional
    public Response crear(CatalogoGeneral catalogo) {
        if (catalogo.tipoCatalogo == null || catalogo.descripcion == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                   .entity("{\"error\": \"El tipo de catálogo y la descripción son obligatorios\"}").build();
        }
        
        catalogo.persist();
        return Response.status(Response.Status.CREATED).entity(catalogo).build();
    }

    // Eliminar un registro del catálogo
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response eliminar(@PathParam("id") Long id) {
        CatalogoGeneral entidad = CatalogoGeneral.findById(id);
        if (entidad == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        entidad.delete();
        return Response.noContent().build();
    }
}