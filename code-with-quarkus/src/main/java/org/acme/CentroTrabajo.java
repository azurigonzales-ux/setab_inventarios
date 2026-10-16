package org.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(indexes = {
    @Index(name = "idx_ct_clave_ct", columnList = "claveCT"),
    @Index(name = "idx_ct_municipio", columnList = "municipio")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class CentroTrabajo extends PanacheEntity {

    @Column(unique = true)
    public String claveCT;

    public String nombreCT;
    public String unidadResp;
    public String subDireccion;
    public String departamento;
    public String director;

    public String tipoVial;
    public String nombreVial;
    public String numExt;
    public String numInt;
    public String tipoAsent;
    public String nomAsent;
    public String codPostal;
    public String municipio;
    public String localidad;

    public String vialidIzq;
    public String vialidDer;
    public String vialidPost;
    public String refVialid;

    public String zonaEscolar;
    public String sector;
}