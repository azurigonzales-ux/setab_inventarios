package org.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogoGeneral extends PanacheEntity {

    public String tipoCatalogo;
    public String clave;
    public String descripcion;
    public String nombre;
}