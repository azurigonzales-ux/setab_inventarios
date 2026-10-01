package org.acme;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "articulo")
public class Articulo extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty("id")
    public Long id;

    @Column(unique = true, length = 22)
    @JsonProperty("numeroInventario")
    public String numeroInventario;

    @JsonProperty("numeroSerie")
    public String numeroSerie;

    @JsonProperty("folioFiscal")
    public String folioFiscal;

    @Column(columnDefinition = "TEXT")
    @JsonProperty("descripcion")
    public String descripcion;

    @JsonProperty("marca")
    public String marca;

    @JsonProperty("modelo")
    public String modelo;

    @JsonProperty("categoria")
    public String categoria;

    @JsonProperty("areaUbicacion")
    public String areaUbicacion;

    @JsonProperty("valorUmas")
    public Double valorUmas;

    @JsonProperty("valorInicial")
    public Double valorInicial;

    @JsonProperty("fechaAdquisicion")
    public LocalDate fechaAdquisicion;

    @JsonProperty("porcentajeDepreciacion")
    public Double porcentajeDepreciacion;

    @JsonProperty("estadoFisico")
    public String estadoFisico;

    @JsonProperty("estado")
    public String estado;

    @JsonProperty("cantidadStock")
    public double cantidadStock;

    @Column(unique = true)
    @JsonProperty("tokenQR")
    public String tokenQR;
}