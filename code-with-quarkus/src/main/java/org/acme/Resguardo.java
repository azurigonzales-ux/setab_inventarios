package org.acme;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
public class Resguardo extends PanacheEntity {
    
    @ManyToOne
    @JoinColumn(name = "id_articulo")
    public Articulo articulo;

    @ManyToOne
    @JoinColumn(name = "id_empleado")
    public Empleado empleado;
    
    @JsonDeserialize(using = Articulo.LocalDateFlexibleDeserializer.class)
    public LocalDate fechaAsignacion;
    
    public double cantidadAsignada; 
    
    public double cantidadPendiente;

    @Column(columnDefinition = "TEXT")
    public String firmaEmpleadoBase64;

    @Column(columnDefinition = "TEXT")
    public String firmaAdminBase64;

    public String nombreAdminAsignador;

    public String latitudGPS;
    
    public String longitudGPS;
    
    public Boolean firmaVerificadaAdmin = false;

    @Column(length = 30)
    public String estadoResguardo = "ACTIVO"; 

    @Column(columnDefinition = "TEXT")
    public String firmaSuperAdminBajaBase64;

    @Column(columnDefinition = "TEXT")
    public String firmaDirectorBajaBase64;

    @JsonDeserialize(using = Articulo.LocalDateFlexibleDeserializer.class)
    public LocalDate fechaBaja;

    public String folioResguardoOficial;

    public String fondoOrigen;
}
