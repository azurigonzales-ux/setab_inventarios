package org.acme;
import java.time.LocalDate;

public class ResguardoDTO {
    public Long idResguardo;
    public Long idArticulo;
    public String equipoSerie;
    public String equipoMarca;
    public Long idEmpleado;
    public String nombreEmpleado;
    public String areaDestino;
    public LocalDate fechaAsignacion;
    public double cantidadAsignada; // <-- PARA ENVIARLO AL FRONTEND
}