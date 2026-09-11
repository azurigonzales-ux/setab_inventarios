package org.acme;

import java.time.LocalDateTime;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "historial_trazabilidad")
public class HistorialTrazabilidad extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_trazabilidad")
    public Long idTrazabilidad;

    // Relación con tu artículo existente
    @ManyToOne
    @JoinColumn(name = "id_articulo")
    public Articulo articulo;

    public String username;

    @Column(name = "tipo_movimiento")
    public String tipoMovimiento;

    @Column(name = "detalles_modificacion")
    public String detallesModificacion;

    @Column(name = "fecha_movimiento")
    public LocalDateTime fechaMovimiento;
}