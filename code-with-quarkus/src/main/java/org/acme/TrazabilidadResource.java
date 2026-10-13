package org.acme;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.RolesAllowed;
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
@RolesAllowed({"SUPERADMIN"})
public class TrazabilidadResource {

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
            List<HistorialTrazabilidad> historial = HistorialTrazabilidad.find(
                "articulo.id = ?1 order by fechaMovimiento desc", idArticulo
            ).list();
            
            List<MovimientoDTO> listaSegura = new ArrayList<>();
            
            for (HistorialTrazabilidad h : historial) {
                MovimientoDTO dto = new MovimientoDTO();
                dto.idTrazabilidad = h.idTrazabilidad;
                dto.tipoMovimiento = h.tipoMovimiento;
                dto.detallesModificacion = h.detallesModificacion;
                dto.fechaMovimiento = h.fechaMovimiento;
                dto.username = h.username;
                
                listaSegura.add(dto);
            }
            
            return Response.ok(listaSegura).build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity("{\"error\": \"Fallo interno: " + e.getMessage() + "\"}").build();
        }
    }
}