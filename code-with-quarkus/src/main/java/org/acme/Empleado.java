package org.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "empleado", indexes = {
    @Index(name = "idx_empleado_cct", columnList = "cct"),
    @Index(name = "idx_empleado_usuario", columnList = "usuario"),
    @Index(name = "idx_empleado_rfc", columnList = "rfc")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class Empleado extends PanacheEntityBase {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty("id_empleado")
    public Long idEmpleado;
    
    public String nombre;
    
    @JsonProperty("num_empleado")
    public String numeroEmpleado;
    
    public String rfc;
    
    public String cct;
    
    public String nombreEscuela;
    
    public String usuario;
    
    public String cargo;
    
    public String area;
}