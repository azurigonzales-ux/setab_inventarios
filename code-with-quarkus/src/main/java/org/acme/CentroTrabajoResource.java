package org.acme;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/centros-trabajo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CentroTrabajoResource {

    // Obtener todas las escuelas (Para la tabla del frontend)
    @GET
    public List<CentroTrabajo> listarTodos() {
        return CentroTrabajo.listAll();
    }

    // Guardar una nueva escuela
    @POST
    @Transactional
    public Response crear(CentroTrabajo centro) {
        // Validamos que no exista ya esa clave
        if (CentroTrabajo.findById(centro.claveCT) != null) {
            return Response.status(Response.Status.CONFLICT)
                   .entity("{\"error\": \"La Clave CCT ya existe en el sistema\"}").build();
        }
        
        centro.persist();
        return Response.status(Response.Status.CREATED).entity(centro).build();
    }

    // Actualizar escuela existente
    @PUT
    @Path("/{clave}")
    @Transactional
    public Response actualizar(@PathParam("clave") String clave, CentroTrabajo datosNuevos) {
        CentroTrabajo entidad = CentroTrabajo.findById(clave);
        if (entidad == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        // Actualizamos los campos
        entidad.nombreCT = datosNuevos.nombreCT;
        entidad.director = datosNuevos.director;
        entidad.municipio = datosNuevos.municipio;
        // (Aquí puedes igualar el resto de los campos si es necesario)
        
        return Response.ok(entidad).build();
    }
}