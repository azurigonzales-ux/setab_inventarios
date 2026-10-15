package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "centro_trabajo")
public class CentroTrabajo extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

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