package org.acme;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/bienes") 
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SUPERADMIN"}) 
public class ArticuloResource {

    @Inject
    JsonWebToken jwt;

    private String obtenerNombreAdmin() {
        String adminNombre = "Administrador del Sistema";
        if (jwt != null && jwt.getName() != null) {
            Usuario adminUser = Usuario.find("username", jwt.getName()).firstResult();
            if (adminUser != null && adminUser.nombreCompleto != null && !adminUser.nombreCompleto.isEmpty()) {
                adminNombre = adminUser.nombreCompleto;
            } else {
                adminNombre = jwt.getName();
            }
        }
        return adminNombre;
    }

    public static class PaginacionResponse {
        public java.util.List<Articulo> articulos;
        public int pageCount;
        public long totalRegistros;
    }

    @GET
    public Response listar(
        @QueryParam("categoria") String categoria,
        @QueryParam("area") String area,
        @QueryParam("page") Integer page,
        @QueryParam("size") Integer size
    ) {
        StringBuilder query = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (categoria != null && !categoria.trim().isEmpty()) {
            query.append(" and categoria = :categoria");
            params.put("categoria", categoria);
        }
        
        if (area != null && !area.trim().isEmpty()) {
            query.append(" and areaUbicacion = :area");
            params.put("area", area);
        }

        PanacheQuery<Articulo> baseQuery = Articulo.find(query.toString(), params);
        
        if (page != null && size != null) {
            baseQuery.page(page, size);
        }

        PaginacionResponse respuesta = new PaginacionResponse();
        respuesta.articulos = baseQuery.list();
        respuesta.pageCount = baseQuery.pageCount();
        respuesta.totalRegistros = baseQuery.count();

        return Response.ok(respuesta).build();
    }

    @POST
    @Transactional
    public Response guardar(Articulo articulo) {
        articulo.persistAndFlush();

        int año = LocalDate.now().getYear();
        String catCorta = (articulo.categoria != null && articulo.categoria.length() >= 4) ? articulo.categoria.substring(0, 4).toUpperCase() : "GRAL";
        String baseLimpia = año + catCorta + articulo.id;
        
        articulo.numeroInventario = String.format("%22s", baseLimpia).replace(' ', '0');
        articulo.tokenQR = UUID.randomUUID().toString();
        
        if (articulo.fechaAdquisicion == null) {
            articulo.fechaAdquisicion = LocalDate.now();
        }

        articulo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "ALTA";
        historial.detallesModificacion = "Alta en sistema. Folio Asignado: " + articulo.numeroInventario;
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();

        return Response.status(Response.Status.CREATED).entity(articulo).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response actualizar(@PathParam("id") Long id, Articulo articuloActualizado) {
        Articulo articuloExistente = Articulo.findById(id);
        
        if (articuloExistente == null) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity("El artículo con ID " + id + " no existe.")
                           .build();
        }
        
        articuloExistente.numeroSerie = articuloActualizado.numeroSerie;
        articuloExistente.descripcion = articuloActualizado.descripcion;
        articuloExistente.marca = articuloActualizado.marca;
        articuloExistente.modelo = articuloActualizado.modelo;
        articuloExistente.categoria = articuloActualizado.categoria;
        articuloExistente.areaUbicacion = articuloActualizado.areaUbicacion;
        articuloExistente.cantidadStock = articuloActualizado.cantidadStock;
        articuloExistente.estadoFisico = articuloActualizado.estadoFisico;
        articuloExistente.estado = articuloActualizado.estado;
        articuloExistente.valorUmas = articuloActualizado.valorUmas;
        articuloExistente.valorInicial = articuloActualizado.valorInicial;
        articuloExistente.porcentajeDepreciacion = articuloActualizado.porcentajeDepreciacion;
        
        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articuloExistente;
        historial.tipoMovimiento = "EDICION";
        historial.detallesModificacion = "Se actualizaron las características o el stock del artículo.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();
        
        return Response.ok(articuloExistente).build();
    }
}