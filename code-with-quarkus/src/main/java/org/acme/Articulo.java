package org.acme;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Articulo extends PanacheEntity {

    public String numeroInventario;
    public String tokenQR;
    public String tipoMaterial;
    public String subClave;

    @Column(length = 500)
    public String descripcion;

    public String marca;
    public String modelo;
    public String numeroSerie;
    public String color;
    public String categoria;
    public String areaUbicacion;
    public Double cantidadStock = 1.0;
    public String estadoFisico;
    public String estado = "Activo";

    public String proveedor;
    public String factura;
    public LocalDate fechaFactura;
    public LocalDate fechaAdquisicion;

    public Double precioSinIva;
    public Double valorInicial;
    public Double valorUmas;
    public Double porcentajeDepreciacion;

    public String pedido;
    public String requisicion;
    public String naturalezaGasto;
    public String partida;

    public String motor;
    public String placa;
    public String poliza;
    public LocalDate vigenciaPoliza;

    @JsonProperty("serie")
    public void setSerieAlias(String serie) {
        if (serie != null && !serie.trim().isEmpty()) {
            this.numeroSerie = serie.trim().toUpperCase();
        }
    }

    @JsonProperty("serie")
    public String getSerieAlias() {
        return this.numeroSerie;
    }

    @JsonProperty("precio")
    public void setPrecioAlias(Double precio) {
        if (precio != null) {
            this.valorInicial = precio;
        }
    }

    @JsonProperty("precio")
    public Double getPrecioAlias() {
        return this.valorInicial;
    }
}