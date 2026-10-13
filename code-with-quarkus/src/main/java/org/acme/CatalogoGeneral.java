package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "catalogos_sistema")
public class CatalogoGeneral extends PanacheEntity {
    
    // Aquí guardaremos: "proveedor", "partida", "departamento", "tipo_articulo", etc.
    public String tipoCatalogo; 
    
    public String clave;
    public String descripcion;
    
    // Campo extra opcional por si el catálogo requiere un valor adicional (ej. monto, id_padre)
    public String valorExtra; 
}