package org.acme;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResguardoDTO {
    public Long idResguardo;
    public Long idArticulo;
    public String equipoSerie;
    public String equipoMarca;
    public Long idEmpleado;
    public String nombreEmpleado;
    public String areaDestino;
    @JsonDeserialize(using = Articulo.LocalDateFlexibleDeserializer.class)
    public LocalDate fechaAsignacion;
    public double cantidadAsignada; // <-- PARA ENVIARLO AL FRONTEND
}