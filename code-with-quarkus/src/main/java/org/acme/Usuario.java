package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class Usuario extends PanacheEntity {
    
    public String nombreCompleto;
    
    @Column(unique = true)
    public String username;
    
    public String password;
    
    public String rol;
    
    public String estado = "Activo";
    
    public String cct;


    public String nivelEducativo;
    
}