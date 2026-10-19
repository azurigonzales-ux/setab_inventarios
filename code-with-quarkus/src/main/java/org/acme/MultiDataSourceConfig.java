package org.acme;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Arquitectura Escalable Multi-Base de Datos (SETAB).
 *
 * Permite operar de forma transparente en dos modalidades:
 * 1. Modo Unificado (Actual): Todos los dominios (Inventarios, Centros de Trabajo CCT y Personal RH)
 *    utilizan el DataSource principal gestionado por Quarkus Agroal / Hibernate ORM Panache.
 * 2. Modo Federado / Multi-Origen: Cuando se integren las credenciales externas de los sistemas
 *    institucionales de Centros de Trabajo (CCT) y Recursos Humanos (Personal RH), permite abrir
 *    conexiones dedicadas por dominio sin acoplar los recursos REST ni romper las secuencias JPA.
 */
@ApplicationScoped
@Path("/api/sistema/datasources")
@Produces(MediaType.APPLICATION_JSON)
public class MultiDataSourceConfig {

    public enum OrigenDatos {
        INVENTARIOS,
        INVENTARIO_INTERNO,
        INVENTARIO_OFICIAL,
        INVENTARIO_OFICIAL_DESAGREGADOS,
        ACERVO_HISTORICO,
        CENTROS_TRABAJO_CCT,
        PERSONAL_RH
    }

    @Inject
    DataSource defaultDataSource;

    @ConfigProperty(name = "setab.datasource.interno.enabled", defaultValue = "false")
    boolean internoExternalEnabled;

    @ConfigProperty(name = "setab.datasource.interno.jdbc-url")
    Optional<String> internoJdbcUrl;

    @ConfigProperty(name = "setab.datasource.interno.username")
    Optional<String> internoUsername;

    @ConfigProperty(name = "setab.datasource.interno.password")
    Optional<String> internoPassword;

    @ConfigProperty(name = "setab.datasource.oficial.enabled", defaultValue = "false")
    boolean oficialExternalEnabled;

    @ConfigProperty(name = "setab.datasource.oficial.jdbc-url")
    Optional<String> oficialJdbcUrl;

    @ConfigProperty(name = "setab.datasource.oficial.username")
    Optional<String> oficialUsername;

    @ConfigProperty(name = "setab.datasource.oficial.password")
    Optional<String> oficialPassword;

    @ConfigProperty(name = "setab.datasource.desagregados.enabled", defaultValue = "false")
    boolean desagregadosExternalEnabled;

    @ConfigProperty(name = "setab.datasource.desagregados.jdbc-url")
    Optional<String> desagregadosJdbcUrl;

    @ConfigProperty(name = "setab.datasource.desagregados.username")
    Optional<String> desagregadosUsername;

    @ConfigProperty(name = "setab.datasource.desagregados.password")
    Optional<String> desagregadosPassword;

    @ConfigProperty(name = "setab.datasource.cct.enabled", defaultValue = "false")
    boolean cctExternalEnabled;

    @ConfigProperty(name = "setab.datasource.cct.jdbc-url")
    Optional<String> cctJdbcUrl;

    @ConfigProperty(name = "setab.datasource.cct.username")
    Optional<String> cctUsername;

    @ConfigProperty(name = "setab.datasource.cct.password")
    Optional<String> cctPassword;

    @ConfigProperty(name = "setab.datasource.rh.enabled", defaultValue = "false")
    boolean rhExternalEnabled;

    @ConfigProperty(name = "setab.datasource.rh.jdbc-url")
    Optional<String> rhJdbcUrl;

    @ConfigProperty(name = "setab.datasource.rh.username")
    Optional<String> rhUsername;

    @ConfigProperty(name = "setab.datasource.rh.password")
    Optional<String> rhPassword;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EstadoOrigenDTO {
        public String dominio;
        public String modo;
        public boolean dedicadoActivo;
        public boolean conectado;
        public String detalle;
    }

    public OrigenDatos resolverOrigenPorFondo(String fondoOrigen) {
        if (fondoOrigen == null || fondoOrigen.isBlank()) {
            return OrigenDatos.ACERVO_HISTORICO;
        }
        String normalizado = fondoOrigen.trim().toUpperCase();
        if ("INTERNO".equals(normalizado)) {
            return OrigenDatos.INVENTARIO_INTERNO;
        }
        if ("OFICIAL".equals(normalizado)) {
            return OrigenDatos.INVENTARIO_OFICIAL;
        }
        if ("OFICIAL_DESEGREGADOS".equals(normalizado) || "OFICIAL_DESAGREGADOS".equals(normalizado)) {
            return OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS;
        }
        return OrigenDatos.ACERVO_HISTORICO;
    }

    /**
     * Obtiene una conexión SQL para el dominio solicitado.
     * Si el origen externo está habilitado y cuenta con credenciales configuradas,
     * abre la conexión dedicada; de lo contrario, utiliza el pool primario de Inventarios.
     */
    public Connection obtenerConexion(OrigenDatos origen) throws SQLException {
        DriverManager.setLoginTimeout(5);
        if (origen == OrigenDatos.INVENTARIO_INTERNO && internoExternalEnabled && internoJdbcUrl.isPresent() && !internoJdbcUrl.get().isBlank()) {
            return DriverManager.getConnection(
                    internoJdbcUrl.get().trim(),
                    internoUsername.orElse(""),
                    internoPassword.orElse("")
            );
        }
        if (origen == OrigenDatos.INVENTARIO_OFICIAL && oficialExternalEnabled && oficialJdbcUrl.isPresent() && !oficialJdbcUrl.get().isBlank()) {
            return DriverManager.getConnection(
                    oficialJdbcUrl.get().trim(),
                    oficialUsername.orElse(""),
                    oficialPassword.orElse("")
            );
        }
        if (origen == OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS && desagregadosExternalEnabled && desagregadosJdbcUrl.isPresent() && !desagregadosJdbcUrl.get().isBlank()) {
            return DriverManager.getConnection(
                    desagregadosJdbcUrl.get().trim(),
                    desagregadosUsername.orElse(""),
                    desagregadosPassword.orElse("")
            );
        }
        if (origen == OrigenDatos.CENTROS_TRABAJO_CCT && cctExternalEnabled && cctJdbcUrl.isPresent() && !cctJdbcUrl.get().isBlank()) {
            return DriverManager.getConnection(
                    cctJdbcUrl.get().trim(),
                    cctUsername.orElse(""),
                    cctPassword.orElse("")
            );
        }
        if (origen == OrigenDatos.PERSONAL_RH && rhExternalEnabled && rhJdbcUrl.isPresent() && !rhJdbcUrl.get().isBlank()) {
            return DriverManager.getConnection(
                    rhJdbcUrl.get().trim(),
                    rhUsername.orElse(""),
                    rhPassword.orElse("")
            );
        }
        return defaultDataSource.getConnection();
    }

    @GET
    @RolesAllowed("SUPERADMIN")
    public Response verificarEstadoOrigenes() {
        Map<String, EstadoOrigenDTO> estado = new LinkedHashMap<>();
        estado.put("inventarios", evaluarOrigen(OrigenDatos.INVENTARIOS, false));
        estado.put("inventarioInterno", evaluarOrigen(OrigenDatos.INVENTARIO_INTERNO, internoExternalEnabled));
        estado.put("inventarioOficial", evaluarOrigen(OrigenDatos.INVENTARIO_OFICIAL, oficialExternalEnabled));
        estado.put("inventarioOficialDesagregados", evaluarOrigen(OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS, desagregadosExternalEnabled));
        estado.put("acervoHistorico", evaluarOrigen(OrigenDatos.ACERVO_HISTORICO, false));
        estado.put("centrosTrabajoCCT", evaluarOrigen(OrigenDatos.CENTROS_TRABAJO_CCT, cctExternalEnabled));
        estado.put("personalRH", evaluarOrigen(OrigenDatos.PERSONAL_RH, rhExternalEnabled));
        return Response.ok(estado).build();
    }

    private EstadoOrigenDTO evaluarOrigen(OrigenDatos origen, boolean dedicadoHabilitado) {
        EstadoOrigenDTO dto = new EstadoOrigenDTO();
        dto.dominio = origen.name();
        dto.dedicadoActivo = dedicadoHabilitado;
        dto.modo = dedicadoHabilitado ? "EXTERNO_DEDICADO" : "UNIFICADO_DEFAULT";
        try (Connection conn = obtenerConexion(origen)) {
            dto.conectado = conn != null && conn.isValid(3);
            dto.detalle = dto.conectado ? "Conexión verificada correctamente" : "Conexión no válida";
        } catch (Exception e) {
            dto.conectado = false;
            dto.detalle = e.getMessage();
        }
        return dto;
    }
}
