package org.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "centros_trabajo")
public class CentroTrabajo extends PanacheEntityBase {

    @Id
    @Column(name = "clave_ct", length = 50, nullable = false)
    public String claveCT; // Este será nuestro ID principal (ej. 27DPR0000X)

    public String nombreCT;
    public String unidadResp;
    public String subDireccion;
    public String departamento;
    
    // Datos Geográficos
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
    
    // Organización
    public String zonaEscolar;
    public String sector;
    public String director;
}