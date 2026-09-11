package org.acme;

import java.time.LocalDate;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Resguardo extends PanacheEntity {
    
    @ManyToOne
    @JoinColumn(name = "id_articulo")
    public Articulo articulo;

    @ManyToOne
    @JoinColumn(name = "id_empleado")
    public Empleado empleado;
    
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
}