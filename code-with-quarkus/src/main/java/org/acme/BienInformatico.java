package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

// Esta etiqueta es la que le avisa a Quarkus que esto debe ser una tabla en PostgreSQL
@Entity
public class BienInformatico extends PanacheEntity {
    
    // Cada una de estas variables se va a convertir en una columna de tu tabla
    public String numeroInventario;
    public String tipoBien;
    public String marca;
    public String modelo;
    public String responsable;

}