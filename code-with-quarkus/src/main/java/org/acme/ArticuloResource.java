package org.acme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Inject
    PostgresRelacionalCrudService relacionalService;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImagenBienRequest {
        public String imagenBienBase64;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReclasificarFondoRequest {
        public String nuevoFondo;
        public Boolean regenerarFolio;
    }

    private String extraerClavePartida(String partidaRaw) {
        if (partidaRaw == null || partidaRaw.isBlank()) {
            return "";
        }
        String t = partidaRaw.trim().toUpperCase();
        int idxGuion = t.indexOf(" - ");
        if (idxGuion > 0) {
            t = t.substring(0, idxGuion).trim();
        }
        int idxEsp = t.indexOf(' ');
        if (idxEsp > 0) {
            t = t.substring(0, idxEsp).trim();
        }
        return t.replaceAll("[^0-9A-Z]", "");
    }

    private long calcularSiguienteConsecutivoPorFondo(String fondo) {
        String f = (fondo != null && !fondo.isBlank()) ? fondo.trim().toUpperCase() : "OFICIAL";
        long totalFondo = Articulo.count("upper(fondoOrigen) = ?1", f);
        int anioCorto = LocalDate.now().getYear() % 100;
        String prefijo = "INTERNO".equals(f)
                ? ("SE-AG-" + String.format("%02d", anioCorto) + "-")
                : ("SE-OF-" + String.format("%02d", anioCorto) + "-");
        long maxSufijo = 0L;
        List<Articulo> existentes = Articulo.find("upper(fondoOrigen) = ?1", f).list();
        for (Articulo art : existentes) {
            if (art == null || art.numeroInventario == null) continue;
            String num = art.numeroInventario.trim().toUpperCase();
            if (num.startsWith(prefijo) && num.length() > prefijo.length()) {
                try {
                    long val = Long.parseLong(num.substring(prefijo.length()).trim());
                    if (val > maxSufijo) maxSufijo = val;
                } catch (NumberFormatException ignored) {
                }
            } else {
                String[] partes = num.split("-");
                if (partes.length >= 3) {
                    try {
                        long val = Long.parseLong(partes[partes.length - 2]);
                        if (val > maxSufijo) maxSufijo = val;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return Math.max(totalFondo + 1L, maxSufijo + 1L);
    }

    private String construirFolioPorFondoYConsecutivo(String fondo, String partidaRaw, long consecutivo) {
        String f = (fondo != null && !fondo.isBlank()) ? fondo.trim().toUpperCase() : "OFICIAL";
        int anioCorto = LocalDate.now().getYear() % 100;
        String clavePartida = extraerClavePartida(partidaRaw);
        String candidato;
        long seq = consecutivo;
        do {
            if ("INTERNO".equals(f)) {
                candidato = String.format("SE-AG-%02d-%05d", anioCorto, seq);
            } else if (!clavePartida.isEmpty()) {
                candidato = String.format("%s-0001-%05d-%02d", clavePartida, seq, anioCorto);
            } else {
                candidato = String.format("SE-OF-%02d-%05d", anioCorto, seq);
            }
            seq++;
        } while (Articulo.count("upper(numeroInventario) = ?1", candidato.toUpperCase()) > 0);
        return candidato;
    }

    private Articulo clonarDatosBase(Articulo base) {
        Articulo c = new Articulo();
        c.numeroInventarioAnterior = base.numeroInventarioAnterior;
        c.fondoOrigen = base.fondoOrigen;
        c.tipoMaterial = base.tipoMaterial;
        c.subClave = base.subClave;
        c.cuentaContable = base.cuentaContable;
        c.descripcion = base.descripcion;
        c.descripcionSecundaria = base.descripcionSecundaria;
        c.marca = base.marca;
        c.modelo = base.modelo;
        c.numeroSerie = base.numeroSerie;
        c.color = base.color;
        c.categoria = base.categoria;
        c.areaUbicacion = base.areaUbicacion;
        c.unidadResponsable = base.unidadResponsable;
        c.subdireccion = base.subdireccion;
        c.departamento = base.departamento;
        c.centroTrabajo = base.centroTrabajo;
        c.resguardanteActual = base.resguardanteActual;
        c.resguardanteHistorico = base.resguardanteHistorico;
        c.folioResguardo = base.folioResguardo;
        c.tipoFolioResguardo = base.tipoFolioResguardo;
        c.documentoResguardo = base.documentoResguardo;
        c.fechaResguardo = base.fechaResguardo;
        c.cantidadStock = 1.0;
        c.estadoFisico = (base.estadoFisico != null && !base.estadoFisico.trim().isEmpty()) ? base.estadoFisico : "NUEVO";
        c.estado = (base.estado != null && !base.estado.trim().isEmpty()) ? base.estado : "Activo";
        c.proveedor = base.proveedor;
        c.factura = base.factura;
        c.folioFiscalUuid = base.folioFiscalUuid;
        c.boletaSalida = base.boletaSalida;
        c.fechaFactura = base.fechaFactura;
        c.fechaAdquisicion = base.fechaAdquisicion != null ? base.fechaAdquisicion : LocalDate.now();
        c.precioSinIva = base.precioSinIva;
        c.valorInicial = base.valorInicial;
        c.valorUmas = base.valorUmas;
        c.porcentajeDepreciacion = base.porcentajeDepreciacion;
        c.pedido = base.pedido;
        c.requisicion = base.requisicion;
        c.naturalezaGasto = base.naturalezaGasto;
        c.partida = base.partida;
        c.motor = base.motor;
        c.placa = base.placa;
        c.poliza = base.poliza;
        c.vigenciaDesde = base.vigenciaDesde;
        c.vigenciaPoliza = base.vigenciaPoliza;
        c.tipoBaja = base.tipoBaja;
        c.carpetaInvestigacion = base.carpetaInvestigacion;
        c.oficioBaja = base.oficioBaja;
        c.fechaBaja = base.fechaBaja;
        c.numeroActa = base.numeroActa;
        c.fechaActa = base.fechaActa;
        c.imagenBienBase64 = base.imagenBienBase64;
        c.fechaUltimaActualizacionAnual = base.fechaUltimaActualizacionAnual;
        c.estadoVerificacionAnual = base.estadoVerificacionAnual != null ? base.estadoVerificacionAnual : "PENDIENTE";
        return c;
    }

    @GET
    public Response listar(
        @QueryParam("busqueda") String busqueda,
        @QueryParam("indiceBusqueda") String indiceBusqueda,
        @QueryParam("fondo") String fondo,
        @QueryParam("categoria") String categoria,
        @QueryParam("area") String area,
        @QueryParam("page") Integer page,
        @QueryParam("size") Integer size
    ) {
        if (relacionalService != null && relacionalService.puedeOperarInventario(fondo)) {
            try {
                PaginacionResponse respRelacional = relacionalService.listarArticulos(
                        busqueda, indiceBusqueda, fondo, categoria, area, page, size
                );
                return Response.ok(respRelacional).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Error consultando tabla inventario externa: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }

        StringBuilder query = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (busqueda != null && !busqueda.trim().isEmpty()) {
            String ind = indiceBusqueda != null ? indiceBusqueda.trim().toUpperCase() : "TODOS";
            if ("INVENTARIO".equals(ind)) {
                query.append(" and (lower(numeroInventario) like :busqueda or lower(numeroInventarioAnterior) like :busqueda)");
            } else if ("SERIE".equals(ind)) {
                query.append(" and lower(numeroSerie) like :busqueda");
            } else if ("CCT_RESGUARDANTE".equals(ind) || "RESGUARDANTE_CCT".equals(ind)) {
                query.append(" and (lower(centroTrabajo) like :busqueda or lower(resguardanteActual) like :busqueda or lower(resguardanteHistorico) like :busqueda or lower(areaUbicacion) like :busqueda)");
            } else if ("TIPO_PARTIDA".equals(ind)) {
                query.append(" and (lower(tipoMaterial) like :busqueda or lower(subClave) like :busqueda or lower(partida) like :busqueda or lower(cuentaContable) like :busqueda)");
            } else {
                query.append(" and (lower(marca) like :busqueda or lower(modelo) like :busqueda or lower(descripcion) like :busqueda or lower(descripcionSecundaria) like :busqueda or lower(numeroInventario) like :busqueda or lower(numeroInventarioAnterior) like :busqueda or lower(numeroSerie) like :busqueda or lower(centroTrabajo) like :busqueda or lower(resguardanteActual) like :busqueda or lower(tipoMaterial) like :busqueda or lower(partida) like :busqueda or lower(factura) like :busqueda or lower(proveedor) like :busqueda)");
            }
            params.put("busqueda", "%" + busqueda.trim().toLowerCase() + "%");
        }

        if (fondo != null && !fondo.trim().isEmpty()) {
            query.append(" and upper(fondoOrigen) = :fondo");
            params.put("fondo", fondo.trim().toUpperCase());
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
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response buscarPorFolio(@PathParam("folio") String folio) {
        if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
            try {
                Articulo ext = relacionalService.buscarPorFolio(folio);
                if (ext != null) {
                    return Response.ok(ext).build();
                }
            } catch (Exception ignored) {
            }
        }
        String f = folio.trim().toUpperCase();
        Articulo articulo = Articulo.find("upper(numeroInventario) = ?1 or upper(numeroInventarioAnterior) = ?1 or upper(numeroSerie) = ?1 or tokenQR = ?2", f, folio.trim()).firstResult();
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
        if (articulo.fondoOrigen == null || articulo.fondoOrigen.trim().isEmpty()) {
            articulo.fondoOrigen = "OFICIAL";
        } else {
            articulo.fondoOrigen = articulo.fondoOrigen.trim().toUpperCase();
        }

        int totalUnidades = 1;
        if (articulo.cantidadStock != null && articulo.cantidadStock > 1.0) {
            totalUnidades = Math.min(500, Math.max(1, (int) Math.round(articulo.cantidadStock)));
        }

        String adminNombre = obtenerNombreAdmin();

        if (relacionalService != null && relacionalService.puedeOperarInventario(articulo.fondoOrigen)) {
            try {
                Articulo primerCreado = null;
                long baseSeq = calcularSiguienteConsecutivoPorFondo(articulo.fondoOrigen);
                for (int i = 0; i < totalUnidades; i++) {
                    Articulo unidad = clonarDatosBase(articulo);
                    if (totalUnidades > 1 || unidad.numeroInventario == null || unidad.numeroInventario.isBlank() || "AUTOGENERADO".equalsIgnoreCase(unidad.numeroInventario)) {
                        unidad.numeroInventario = construirFolioPorFondoYConsecutivo(articulo.fondoOrigen, articulo.partida, baseSeq + i);
                    }
                    unidad.tokenQR = UUID.randomUUID().toString();
                    Articulo creado = relacionalService.insertarArticulo(unidad, adminNombre);
                    if (primerCreado == null) {
                        primerCreado = creado;
                    }
                }
                return Response.status(Response.Status.CREATED).entity(primerCreado).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Error al insertar en tabla inventario: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }

        String folioSolicitado = (articulo.numeroInventario != null) ? articulo.numeroInventario.trim().toUpperCase() : "";
        long consecutivoBase = calcularSiguienteConsecutivoPorFondo(articulo.fondoOrigen);
        List<Articulo> loteCreado = new ArrayList<>();

        for (int i = 0; i < totalUnidades; i++) {
            Articulo unidad = clonarDatosBase(articulo);
            unidad.id = null;

            if (totalUnidades == 1 && !folioSolicitado.isEmpty() && !folioSolicitado.equals("AUTOGENERADO")
                    && !folioSolicitado.startsWith("SE-AG-")
                    && Articulo.count("upper(numeroInventario) = ?1", folioSolicitado) == 0) {
                unidad.numeroInventario = folioSolicitado;
            } else {
                unidad.numeroInventario = construirFolioPorFondoYConsecutivo(unidad.fondoOrigen, unidad.partida, consecutivoBase + i);
            }
            unidad.tokenQR = UUID.randomUUID().toString();
            unidad.persistAndFlush();

            sincronizarResguardoInicialSiAplica(unidad, adminNombre);

            HistorialTrazabilidad historial = new HistorialTrazabilidad();
            historial.articulo = unidad;
            historial.tipoMovimiento = "ALTA_EN_BODEGA";
            String infoLote = totalUnidades > 1 ? (" [Alta por Lote: unidad " + (i + 1) + " de " + totalUnidades + "]") : "";
            historial.detallesModificacion = "Alta en bodega (" + unidad.fondoOrigen + "). Folio de Inventario Asignado: " + unidad.numeroInventario + infoLote;
            historial.fechaMovimiento = LocalDateTime.now();
            historial.username = adminNombre;
            historial.persist();

            loteCreado.add(unidad);
        }

        return Response.status(Response.Status.CREATED).entity(loteCreado.get(0)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response actualizar(@PathParam("id") Long id, Articulo articuloActualizado) {
        String fondoRef = (articuloActualizado != null && articuloActualizado.fondoOrigen != null) ? articuloActualizado.fondoOrigen : null;
        if (relacionalService != null && relacionalService.puedeOperarInventario(fondoRef)) {
            try {
                Articulo actualizado = relacionalService.actualizarArticulo(id, articuloActualizado, obtenerNombreAdmin());
                return Response.ok(actualizado).build();
            } catch (Exception e) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\": \"Error al actualizar en tabla inventario: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }

        Articulo articuloExistente = Articulo.findById(id);
        
        if (articuloExistente == null) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity("{\"error\": \"El artículo con ID " + id + " no existe.\"}")
                           .build();
        }
        
        articuloExistente.numeroInventarioAnterior = articuloActualizado.numeroInventarioAnterior;
        articuloExistente.fondoOrigen = (articuloActualizado.fondoOrigen != null && !articuloActualizado.fondoOrigen.trim().isEmpty())
                ? articuloActualizado.fondoOrigen.trim().toUpperCase()
                : (articuloExistente.fondoOrigen != null ? articuloExistente.fondoOrigen : "OFICIAL");
        articuloExistente.cuentaContable = articuloActualizado.cuentaContable;
        articuloExistente.numeroSerie = articuloActualizado.numeroSerie;
        articuloExistente.descripcion = articuloActualizado.descripcion;
        articuloExistente.descripcionSecundaria = articuloActualizado.descripcionSecundaria;
        articuloExistente.marca = articuloActualizado.marca;
        articuloExistente.modelo = articuloActualizado.modelo;
        articuloExistente.categoria = articuloActualizado.categoria;
        articuloExistente.areaUbicacion = articuloActualizado.areaUbicacion;
        articuloExistente.unidadResponsable = articuloActualizado.unidadResponsable;
        articuloExistente.subdireccion = articuloActualizado.subdireccion;
        articuloExistente.departamento = articuloActualizado.departamento;
        articuloExistente.centroTrabajo = articuloActualizado.centroTrabajo;
        articuloExistente.resguardanteActual = articuloActualizado.resguardanteActual;
        articuloExistente.resguardanteHistorico = articuloActualizado.resguardanteHistorico;
        articuloExistente.folioResguardo = articuloActualizado.folioResguardo;
        articuloExistente.tipoFolioResguardo = articuloActualizado.tipoFolioResguardo;
        articuloExistente.documentoResguardo = articuloActualizado.documentoResguardo;
        articuloExistente.fechaResguardo = articuloActualizado.fechaResguardo;
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
        articuloExistente.folioFiscalUuid = articuloActualizado.folioFiscalUuid;
        articuloExistente.boletaSalida = articuloActualizado.boletaSalida;
        articuloExistente.fechaFactura = articuloActualizado.fechaFactura;
        articuloExistente.precioSinIva = articuloActualizado.precioSinIva;
        articuloExistente.pedido = articuloActualizado.pedido;
        articuloExistente.requisicion = articuloActualizado.requisicion;
        articuloExistente.naturalezaGasto = articuloActualizado.naturalezaGasto;
        articuloExistente.partida = articuloActualizado.partida;
        articuloExistente.motor = articuloActualizado.motor;
        articuloExistente.placa = articuloActualizado.placa;
        articuloExistente.poliza = articuloActualizado.poliza;
        articuloExistente.vigenciaDesde = articuloActualizado.vigenciaDesde;
        articuloExistente.vigenciaPoliza = articuloActualizado.vigenciaPoliza;
        articuloExistente.tipoBaja = articuloActualizado.tipoBaja;
        articuloExistente.carpetaInvestigacion = articuloActualizado.carpetaInvestigacion;
        articuloExistente.oficioBaja = articuloActualizado.oficioBaja;
        articuloExistente.fechaBaja = articuloActualizado.fechaBaja;
        articuloExistente.numeroActa = articuloActualizado.numeroActa;
        articuloExistente.fechaActa = articuloActualizado.fechaActa;
        if (articuloActualizado.imagenBienBase64 != null && !articuloActualizado.imagenBienBase64.isBlank()) {
            articuloExistente.imagenBienBase64 = articuloActualizado.imagenBienBase64;
        }
        if (articuloActualizado.fechaAdquisicion != null) {
            articuloExistente.fechaAdquisicion = articuloActualizado.fechaAdquisicion;
        }
        articuloExistente.persist();

        sincronizarResguardoInicialSiAplica(articuloExistente, obtenerNombreAdmin());
        
        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articuloExistente;
        historial.tipoMovimiento = "EDICION";
        historial.detallesModificacion = "Se actualizaron las características o ficha técnica del artículo (Folio: " + articuloExistente.numeroInventario + ").";
        historial.fechaMovimiento = LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();
        
        return Response.ok(articuloExistente).build();
    }

    @PUT
    @Path("/{id}/imagen")
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    @Transactional
    public Response actualizarImagenBien(@PathParam("id") Long id, ImagenBienRequest request) {
        if (request == null || request.imagenBienBase64 == null || request.imagenBienBase64.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"La evidencia fotográfica en Base64 es obligatoria.\"}")
                    .build();
        }
        Articulo articulo = relacionalService != null
                ? relacionalService.sincronizarArticuloEnPanache(id, null)
                : Articulo.findById(id);
        if (articulo == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"El artículo con ID " + id + " no existe.\"}")
                    .build();
        }
        articulo.imagenBienBase64 = request.imagenBienBase64;
        articulo.persist();

        if (relacionalService != null) {
            relacionalService.actualizarImagenArticulo(id, articulo.numeroInventario, articulo.fondoOrigen, request.imagenBienBase64);
        }

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "EVIDENCIA_FOTOGRAFICA";
        historial.detallesModificacion = "Se cargó o actualizó la evidencia fotográfica física del bien (Folio: " + articulo.numeroInventario + ").";
        historial.fechaMovimiento = LocalDateTime.now();
        historial.username = obtenerNombreAdmin();
        historial.persist();

        return Response.ok(articulo).build();
    }

    @PUT
    @Path("/{id}/reclasificar-fondo")
    @RolesAllowed({"SUPERADMIN"})
    @Transactional
    public Response reclasificarFondo(@PathParam("id") Long id, ReclasificarFondoRequest request) {
        if (request == null || request.nuevoFondo == null || request.nuevoFondo.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Debe indicar el nuevo fondo operativo destino.\"}")
                    .build();
        }
        Articulo articulo = relacionalService != null
                ? relacionalService.sincronizarArticuloEnPanache(id, null)
                : Articulo.findById(id);
        if (articulo == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"El artículo con ID " + id + " no existe.\"}")
                    .build();
        }

        String fondoAnterior = articulo.fondoOrigen != null ? articulo.fondoOrigen : "OFICIAL";
        String nuevoFondo = request.nuevoFondo.trim().toUpperCase();
        String folioAnterior = articulo.numeroInventario;

        articulo.fondoOrigen = nuevoFondo;
        if (request.regenerarFolio == null || Boolean.TRUE.equals(request.regenerarFolio)) {
            articulo.numeroInventarioAnterior = folioAnterior;
            long sig = calcularSiguienteConsecutivoPorFondo(nuevoFondo);
            articulo.numeroInventario = construirFolioPorFondoYConsecutivo(nuevoFondo, articulo.partida, sig);
        }
        articulo.persist();

        List<Resguardo> resguardosAsociados = Resguardo.find("articulo.id = ?1", articulo.id).list();
        for (Resguardo r : resguardosAsociados) {
            r.fondoOrigen = nuevoFondo;
            r.persist();
        }

        if (relacionalService != null) {
            relacionalService.reclasificarFondoArticulo(id, articulo, fondoAnterior, nuevoFondo, obtenerNombreAdmin());
        }

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "RECLASIFICACION_FONDO";
        historial.detallesModificacion = "Reclasificación de base por corrección operativa: de " + fondoAnterior + " (Folio ant: " + folioAnterior + ") hacia " + nuevoFondo + " (Folio actual: " + articulo.numeroInventario + ").";
        historial.fechaMovimiento = LocalDateTime.now();
        historial.username = obtenerNombreAdmin();
        historial.persist();

        return Response.ok(articulo).build();
    }

    private void sincronizarResguardoInicialSiAplica(Articulo unidad, String adminNombre) {
        if (unidad == null || unidad.id == null) {
            return;
        }
        String resp = unidad.resguardanteActual != null ? unidad.resguardanteActual.trim().toUpperCase() : "";
        String cct = unidad.centroTrabajo != null ? unidad.centroTrabajo.trim().toUpperCase() : "";
        if (resp.isEmpty() && (cct.isEmpty() || "CENTRAL".equals(cct) || "ALMACÉN CENTRAL".equals(cct) || "ALMACEN CENTRAL".equals(cct))) {
            return;
        }

        long resguardosActivos = Resguardo.count(
                "articulo.id = ?1 and (estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA' or estadoResguardo = 'ACTIVO' or estadoResguardo = 'SOLICITUD_BAJA' or estadoResguardo = 'BAJA_EN_PROCESO')",
                unidad.id
        );
        if (resguardosActivos > 0) {
            return;
        }

        Empleado empleadoDestino = null;
        if (!resp.isEmpty()) {
            empleadoDestino = Empleado.find(
                    "upper(nombre) = ?1 or upper(rfc) = ?1 or upper(usuario) = ?1 or upper(claveResponsable) = ?1",
                    resp
            ).firstResult();
        }
        if (empleadoDestino == null && !cct.isEmpty() && !"CENTRAL".equals(cct) && !"ALMACÉN CENTRAL".equals(cct) && !"ALMACEN CENTRAL".equals(cct)) {
            empleadoDestino = Empleado.find("upper(cct) = ?1", cct).firstResult();
        }
        if (empleadoDestino == null) {
            return;
        }

        String fondo = (unidad.fondoOrigen != null && !unidad.fondoOrigen.isBlank())
                ? unidad.fondoOrigen.trim().toUpperCase()
                : "INTERNO";
        String folioOficialNvo;
        if (unidad.folioResguardo != null && !unidad.folioResguardo.isBlank()) {
            folioOficialNvo = unidad.folioResguardo.trim();
        } else {
            long consecutivoFondo = Resguardo.count("upper(fondoOrigen) = ?1", fondo) + 1;
            String anioCorto = String.valueOf(LocalDate.now().getYear()).substring(2);
            folioOficialNvo = String.format("%07d %s-NVO", consecutivoFondo, anioCorto);
            unidad.folioResguardo = folioOficialNvo;
        }

        if (unidad.resguardanteActual == null || unidad.resguardanteActual.isBlank()) {
            unidad.resguardanteActual = empleadoDestino.nombre;
        }
        if ((unidad.centroTrabajo == null || unidad.centroTrabajo.isBlank()) && empleadoDestino.cct != null && !empleadoDestino.cct.isBlank()) {
            unidad.centroTrabajo = empleadoDestino.cct.trim().toUpperCase();
        }
        if (unidad.fechaResguardo == null) {
            unidad.fechaResguardo = LocalDate.now();
        }
        unidad.cantidadStock = 0.0;
        unidad.estado = "Agotado";
        unidad.persist();

        Resguardo resguardo = new Resguardo();
        resguardo.articulo = unidad;
        resguardo.empleado = empleadoDestino;
        resguardo.fechaAsignacion = unidad.fechaResguardo != null ? unidad.fechaResguardo : LocalDate.now();
        resguardo.cantidadAsignada = 1.0;
        resguardo.nombreAdminAsignador = adminNombre != null ? adminNombre : "Almacén Central";
        resguardo.estadoResguardo = "PENDIENTE_FIRMA";
        resguardo.fondoOrigen = fondo;
        resguardo.folioResguardoOficial = folioOficialNvo;
        resguardo.persist();
    }
}
