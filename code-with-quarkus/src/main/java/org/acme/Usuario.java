package org.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(indexes = {
    @Index(name = "idx_usuario_username", columnList = "username"),
    @Index(name = "idx_usuario_cct", columnList = "cct")
})
@JsonIgnoreProperties(ignoreUnknown = true)
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