package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

@Entity
public class CatalogoGeneral extends PanacheEntity {

    public String tipoCatalogo;
    public String clave;
    public String descripcion;
    public String nombre;
}