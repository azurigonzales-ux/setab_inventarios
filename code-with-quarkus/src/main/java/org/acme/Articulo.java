package org.acme;

import java.io.IOException;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(indexes = {
    @Index(name = "idx_articulo_num_inventario", columnList = "numeroInventario"),
    @Index(name = "idx_articulo_num_serie", columnList = "numeroSerie"),
    @Index(name = "idx_articulo_token_qr", columnList = "tokenQR"),
    @Index(name = "idx_articulo_categoria", columnList = "categoria"),
    @Index(name = "idx_articulo_area_ubicacion", columnList = "areaUbicacion")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class Articulo extends PanacheEntity {

    public static class LocalDateFlexibleDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String valor = p.getValueAsString();
            if (valor == null || valor.trim().isEmpty()) {
                return null;
            }
            String limpio = valor.trim();
            try {
                if (limpio.length() > 10 && limpio.contains("T")) {
                    limpio = limpio.substring(0, 10);
                }
                return LocalDate.parse(limpio);
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static class DoubleFlexibleDeserializer extends JsonDeserializer<Double> {
        @Override
        public Double deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String valor = p.getValueAsString();
            if (valor == null || valor.trim().isEmpty()) {
                return null;
            }
            try {
                return Double.valueOf(valor.trim().replace(",", ""));
            } catch (Exception e) {
                return null;
            }
        }
    }

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

    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
    public Double cantidadStock = 1.0;

    public String estadoFisico;
    public String estado = "Activo";

    public String proveedor;
    public String factura;

    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public LocalDate fechaFactura;

    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public LocalDate fechaAdquisicion;

    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
    public Double precioSinIva;

    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
    public Double valorInicial;

    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
    public Double valorUmas;

    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
    public Double porcentajeDepreciacion;

    public String pedido;
    public String requisicion;
    public String naturalezaGasto;
    public String partida;

    public String motor;
    public String placa;
    public String poliza;

    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public LocalDate vigenciaPoliza;

    @JsonSetter("fechaFactura")
    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public void setFechaFactura(LocalDate valor) {
        this.fechaFactura = valor;
    }

    @JsonSetter("vigenciaPoliza")
    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public void setVigenciaPoliza(LocalDate valor) {
        this.vigenciaPoliza = valor;
    }

    @JsonSetter("fechaAdquisicion")
    @JsonDeserialize(using = LocalDateFlexibleDeserializer.class)
    public void setFechaAdquisicion(LocalDate valor) {
        this.fechaAdquisicion = valor;
    }

    public void setFechaFacturaSegura(String valor) {
        if (valor == null || valor.trim().isEmpty()) {
            this.fechaFactura = null;
        } else {
            try {
                this.fechaFactura = LocalDate.parse(valor.trim());
            } catch (Exception e) {
                this.fechaFactura = null;
            }
        }
    }

    public void setVigenciaPolizaSegura(String valor) {
        if (valor == null || valor.trim().isEmpty()) {
            this.vigenciaPoliza = null;
        } else {
            try {
                this.vigenciaPoliza = LocalDate.parse(valor.trim());
            } catch (Exception e) {
                this.vigenciaPoliza = null;
            }
        }
    }

    public void setFechaAdquisicionSegura(String valor) {
        if (valor == null || valor.trim().isEmpty()) {
            this.fechaAdquisicion = null;
        } else {
            try {
                this.fechaAdquisicion = LocalDate.parse(valor.trim());
            } catch (Exception e) {
                this.fechaAdquisicion = null;
            }
        }
    }

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
    @JsonDeserialize(using = DoubleFlexibleDeserializer.class)
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