package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class Usuario extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

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