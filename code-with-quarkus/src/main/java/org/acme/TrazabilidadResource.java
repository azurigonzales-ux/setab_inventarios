package org.acme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/trazabilidad")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SUPERADMIN", "admin", "Admin"})
public class TrazabilidadResource {

    @Inject
    PostgresRelacionalCrudService relacionalService;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MovimientoDTO {
        @JsonProperty("id_trazabilidad")
        public Long idTrazabilidad;

        @JsonProperty("tipo_movimiento")
        public String tipoMovimiento;

        @JsonProperty("detalles_modificacion")
        public String detallesModificacion;

        @JsonProperty("fecha_movimiento")
        public LocalDateTime fechaMovimiento;

        @JsonProperty("usuario")
        public String username;
    }

    @GET
    @Path("/articulo/{idArticulo}")
    public Response obtenerHistorial(@PathParam("idArticulo") Long idArticulo) {
        try {
            List<Long> idsArticulo = new ArrayList<>();
            if (idArticulo != null) {
                idsArticulo.add(idArticulo);
            }

            Articulo artLocal = idArticulo != null ? Articulo.findById(idArticulo) : null;
            Articulo artRelacional = (relacionalService != null && idArticulo != null)
                    ? relacionalService.buscarPorIdRelacional(idArticulo)
                    : null;

            if (artRelacional != null && artRelacional.numeroInventario != null && !artRelacional.numeroInventario.isBlank()) {
                Articulo porNumInv = Articulo.find(
                        "upper(numeroInventario) = ?1",
                        artRelacional.numeroInventario.trim().toUpperCase(Locale.ROOT)
                ).firstResult();
                if (porNumInv != null && porNumInv.id != null && !idsArticulo.contains(porNumInv.id)) {
                    idsArticulo.add(porNumInv.id);
                    if (artLocal == null) {
                        artLocal = porNumInv;
                    }
                }
            }

            List<HistorialTrazabilidad> historial = idsArticulo.isEmpty()
                    ? new ArrayList<>()
                    : HistorialTrazabilidad.find("articulo.id in ?1 order by fechaMovimiento desc", idsArticulo).list();

            List<MovimientoDTO> listaSegura = new ArrayList<>();
            boolean tieneAlta = false;
            boolean tieneAsignacion = false;
            boolean tieneBaja = false;

            for (HistorialTrazabilidad h : historial) {
                MovimientoDTO dto = new MovimientoDTO();
                dto.idTrazabilidad = h.idTrazabilidad;
                dto.tipoMovimiento = h.tipoMovimiento;
                dto.detallesModificacion = h.detallesModificacion;
                dto.fechaMovimiento = h.fechaMovimiento;
                dto.username = h.username;
                if (h.tipoMovimiento != null) {
                    String tm = h.tipoMovimiento.toUpperCase(Locale.ROOT);
                    if (tm.contains("ALTA") || tm.contains("CREACION")) tieneAlta = true;
                    if (tm.contains("ASIGNACION") || tm.contains("RESGUARDO")) tieneAsignacion = true;
                    if (tm.contains("BAJA") || tm.contains("DEVOLUCION")) tieneBaja = true;
                }
                listaSegura.add(dto);
            }

            Articulo ref = artRelacional != null ? artRelacional : artLocal;
            if (ref != null) {
                long synthId = 900_000L + (ref.id != null ? ref.id : 1L);
                if (!tieneBaja && (ref.fechaBaja != null || "Baja".equalsIgnoreCase(ref.estado))) {
                    MovimientoDTO dtoBaja = new MovimientoDTO();
                    dtoBaja.idTrazabilidad = ++synthId;
                    dtoBaja.tipoMovimiento = "BAJA_PATRIMONIAL";
                    dtoBaja.detallesModificacion = "Registro histórico de Baja Patrimonial | Oficio: "
                            + (ref.oficioBaja != null ? ref.oficioBaja : "S/O")
                            + (ref.numeroActa != null ? " | Acta: " + ref.numeroActa : "");
                    dtoBaja.fechaMovimiento = (ref.fechaBaja != null ? ref.fechaBaja : LocalDate.now()).atTime(14, 0);
                    dtoBaja.username = "Almacén Central SETAB";
                    listaSegura.add(dtoBaja);
                }
                boolean tieneCustodia = (ref.centroTrabajo != null && !ref.centroTrabajo.isBlank() && !"CENTRAL".equalsIgnoreCase(ref.centroTrabajo))
                        || (ref.resguardanteActual != null && !ref.resguardanteActual.isBlank());
                if (!tieneAsignacion && tieneCustodia) {
                    MovimientoDTO dtoAsig = new MovimientoDTO();
                    dtoAsig.idTrazabilidad = ++synthId;
                    dtoAsig.tipoMovimiento = "ASIGNACION_RESGUARDO";
                    dtoAsig.detallesModificacion = "Resguardo Oficial registrado en Padrón ["
                            + (ref.fondoOrigen != null ? ref.fondoOrigen : "OFICIAL")
                            + "] | CCT: " + (ref.centroTrabajo != null ? ref.centroTrabajo : "S/C")
                            + " | Resguardante: " + (ref.resguardanteActual != null ? ref.resguardanteActual : "Titular CCT")
                            + (ref.folioResguardo != null ? " | Folio Resguardo: " + ref.folioResguardo : "");
                    LocalDate fecRes = ref.fechaResguardo != null ? ref.fechaResguardo : (ref.fechaAdquisicion != null ? ref.fechaAdquisicion : LocalDate.of(2025, 1, 15));
                    dtoAsig.fechaMovimiento = fecRes.atTime(11, 30);
                    dtoAsig.username = "Control Patrimonial SETAB";
                    listaSegura.add(dtoAsig);
                }
                if (!tieneAlta) {
                    MovimientoDTO dtoAlta = new MovimientoDTO();
                    dtoAlta.idTrazabilidad = ++synthId;
                    dtoAlta.tipoMovimiento = "ALTA_PATRIMONIAL";
                    dtoAlta.detallesModificacion = "Alta e incorporación al Padrón Institucional ["
                            + (ref.fondoOrigen != null ? ref.fondoOrigen : "OFICIAL")
                            + "] | No. Inventario: " + (ref.numeroInventario != null ? ref.numeroInventario : ref.id)
                            + (ref.factura != null ? " | Factura: " + ref.factura : "")
                            + (ref.proveedor != null ? " | Proveedor: " + ref.proveedor : "");
                    LocalDate fecAlta = ref.fechaAdquisicion != null ? ref.fechaAdquisicion : (ref.fechaFactura != null ? ref.fechaFactura : LocalDate.of(2025, 1, 10));
                    dtoAlta.fechaMovimiento = fecAlta.atTime(9, 0);
                    dtoAlta.username = "Almacén Central SETAB";
                    listaSegura.add(dtoAlta);
                }
            }

            return Response.ok(listaSegura).build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity("{\"error\": \"Fallo interno: " + e.getMessage() + "\"}").build();
        }
    }
}
