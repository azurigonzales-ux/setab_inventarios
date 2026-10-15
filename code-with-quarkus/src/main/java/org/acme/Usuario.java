package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Usuario extends PanacheEntity {

    @Column(unique = true)
    public String username;

    public String password;
    public String nombreCompleto;
    public String email;
    public String cct;
    public String nivelEducativo;
    public String nombreEscuela;
    public String rol;
    public String estado = "Activo";
    public String keycloakId;
    public Long idEmpleado;
}