package org.acme;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
            Usuario adminUser = Usuario.find("upper(username) = ?1", jwt.getName().trim().toUpperCase()).firstResult();
            if (adminUser != null && adminUser.nombreCompleto != null && !adminUser.nombreCompleto.isEmpty()) {
                adminNombre = adminUser.nombreCompleto;
            } else {
                adminNombre = jwt.getName();
            }
        }
        return adminNombre;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaginacionResponse {
        public java.util.List<Articulo> articulos;
        public int pageCount;
        public long totalRegistros;
    }

    @GET
    public Response listar(
        @QueryParam("busqueda") String busqueda,
        @QueryParam("categoria") String categoria,
        @QueryParam("area") String area,
        @QueryParam("page") Integer page,
        @QueryParam("size") Integer size
    ) {
        StringBuilder query = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (busqueda != null && !busqueda.trim().isEmpty()) {
            query.append(" and (lower(marca) like :busqueda or lower(modelo) like :busqueda or lower(descripcion) like :busqueda or lower(numeroInventario) like :busqueda or lower(numeroSerie) like :busqueda)");
            params.put("busqueda", "%" + busqueda.trim().toLowerCase() + "%");
        }

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

    @GET
    @Path("/folio/{folio}")
    public Response buscarPorFolio(@PathParam("folio") String folio) {
        String f = folio.trim().toUpperCase();
        Articulo articulo = Articulo.find("upper(numeroInventario) = ?1 or upper(numeroSerie) = ?1 or tokenQR = ?2", f, folio.trim()).firstResult();
        if (articulo == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Equipo no encontrado\"}").build();
        }
        return Response.ok(articulo).build();
    }

    @POST
    @Transactional
    public Response guardar(Articulo articulo) {
        if (articulo == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Los datos del artículo son obligatorios.\"}")
                    .build();
        }
        articulo.id = null;
        articulo.numeroInventario = null;

        if (articulo.cantidadStock == null || articulo.cantidadStock <= 0) {
            articulo.cantidadStock = 1.0;
        }
        if (articulo.estado == null || articulo.estado.trim().isEmpty()) {
            articulo.estado = "Activo";
        }
        if (articulo.estadoFisico == null || articulo.estadoFisico.trim().isEmpty()) {
            articulo.estadoFisico = "NUEVO";
        }

        articulo.persistAndFlush();

        int anioCorto = LocalDate.now().getYear() % 100;
        String prefijoFolio = "SE-AG-" + String.format("%02d", anioCorto) + "-";
        long maxSufijo = 0L;
        List<Articulo> existentesAnio = Articulo.find("upper(numeroInventario) like ?1", (prefijoFolio + "%").toUpperCase()).list();
        for (Articulo art : existentesAnio) {
            if (art != null && art.numeroInventario != null && art.numeroInventario.length() > prefijoFolio.length()) {
                String sufijoStr = art.numeroInventario.substring(prefijoFolio.length()).trim();
                try {
                    long valor = Long.parseLong(sufijoStr);
                    if (valor > maxSufijo) {
                        maxSufijo = valor;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long consecutivo = Math.max(Math.max(1L, Articulo.count()), maxSufijo + 1L);
        String folioCandidato = prefijoFolio + String.format("%05d", consecutivo);
        while (Articulo.count("upper(numeroInventario) = ?1", folioCandidato.toUpperCase()) > 0) {
            consecutivo++;
            folioCandidato = prefijoFolio + String.format("%05d", consecutivo);
        }
        articulo.numeroInventario = folioCandidato;
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
                           .entity("{\"error\": \"El artículo con ID " + id + " no existe.\"}")
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
        articuloExistente.tipoMaterial = articuloActualizado.tipoMaterial;
        articuloExistente.subClave = articuloActualizado.subClave;
        articuloExistente.color = articuloActualizado.color;
        articuloExistente.proveedor = articuloActualizado.proveedor;
        articuloExistente.factura = articuloActualizado.factura;
        articuloExistente.fechaFactura = articuloActualizado.fechaFactura;
        articuloExistente.precioSinIva = articuloActualizado.precioSinIva;
        articuloExistente.pedido = articuloActualizado.pedido;
        articuloExistente.requisicion = articuloActualizado.requisicion;
        articuloExistente.naturalezaGasto = articuloActualizado.naturalezaGasto;
        articuloExistente.partida = articuloActualizado.partida;
        articuloExistente.motor = articuloActualizado.motor;
        articuloExistente.placa = articuloActualizado.placa;
        articuloExistente.poliza = articuloActualizado.poliza;
        articuloExistente.vigenciaPoliza = articuloActualizado.vigenciaPoliza;
        if (articuloActualizado.fechaAdquisicion != null) {
            articuloExistente.fechaAdquisicion = articuloActualizado.fechaAdquisicion;
        }
        
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