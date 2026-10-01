package org.acme;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    // CAJITA SEGURA (DTO) PARA EVITAR BUCLES INFINITOS DE JSON
    public static class MovimientoDTO {
        public Long idTrazabilidad;
        public String tipoMovimiento;
        public String detallesModificacion;
        public LocalDateTime fechaMovimiento;
        public String username;
    }

    @GET
    @Path("/articulo/{idArticulo}")
    public Response obtenerHistorial(@PathParam("idArticulo") Long idArticulo) {
        try {
            // Sintaxis HQL pura y blindada para evitar errores de Panache Sort
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
            // 🚨 SI ALGO FALLA, JUAN LUIS LO VERÁ EN SU TERMINAL 🚨
            System.out.println("============== ERROR EN TRAZABILIDAD ==============");
            e.printStackTrace();
            System.out.println("===================================================");
            
            return Response.serverError().entity("{\"error\": \"Fallo interno: " + e.getMessage() + "\"}").build();
        }
    }
}