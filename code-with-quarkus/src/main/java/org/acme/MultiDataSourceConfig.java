package org.acme;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        PERSONAL_RH,
        ESTADISTICA,
        CONTROL_RIESGO
    }

    @Inject
    DataSource defaultDataSource;

    @ConfigProperty(name = "setab.datasource.inventario.enabled", defaultValue = "true")
    boolean inventarioExternalEnabled;

    @ConfigProperty(name = "setab.datasource.inventario.jdbc-url")
    Optional<String> inventarioJdbcUrl;

    @ConfigProperty(name = "setab.datasource.inventario.username")
    Optional<String> inventarioUsername;

    @ConfigProperty(name = "setab.datasource.inventario.password")
    Optional<String> inventarioPassword;

    @ConfigProperty(name = "setab.datasource.inventario.schema", defaultValue = "Inventario")
    String inventarioSchema;

    @ConfigProperty(name = "setab.datasource.interno.enabled", defaultValue = "true")
    boolean internoExternalEnabled;

    @ConfigProperty(name = "setab.datasource.interno.jdbc-url")
    Optional<String> internoJdbcUrl;

    @ConfigProperty(name = "setab.datasource.interno.username")
    Optional<String> internoUsername;

    @ConfigProperty(name = "setab.datasource.interno.password")
    Optional<String> internoPassword;

    @ConfigProperty(name = "setab.datasource.oficial.enabled", defaultValue = "true")
    boolean oficialExternalEnabled;

    @ConfigProperty(name = "setab.datasource.oficial.jdbc-url")
    Optional<String> oficialJdbcUrl;

    @ConfigProperty(name = "setab.datasource.oficial.username")
    Optional<String> oficialUsername;

    @ConfigProperty(name = "setab.datasource.oficial.password")
    Optional<String> oficialPassword;

    @ConfigProperty(name = "setab.datasource.desagregados.enabled", defaultValue = "true")
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

    @ConfigProperty(name = "setab.datasource.estadistica.enabled", defaultValue = "false")
    boolean estadisticaExternalEnabled;

    @ConfigProperty(name = "setab.datasource.estadistica.jdbc-url")
    Optional<String> estadisticaJdbcUrl;

    @ConfigProperty(name = "setab.datasource.estadistica.username")
    Optional<String> estadisticaUsername;

    @ConfigProperty(name = "setab.datasource.estadistica.password")
    Optional<String> estadisticaPassword;

    @ConfigProperty(name = "setab.datasource.riesgo.enabled", defaultValue = "false")
    boolean riesgoExternalEnabled;

    @ConfigProperty(name = "setab.datasource.riesgo.jdbc-url")
    Optional<String> riesgoJdbcUrl;

    @ConfigProperty(name = "setab.datasource.riesgo.username")
    Optional<String> riesgoUsername;

    @ConfigProperty(name = "setab.datasource.riesgo.password")
    Optional<String> riesgoPassword;

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

    public boolean tieneConexionExternaActiva(OrigenDatos origen) {
        if (origen == OrigenDatos.INVENTARIOS) {
            return inventarioExternalEnabled && obtenerUrlInventarioUnificado().isPresent();
        }
        if (origen == OrigenDatos.INVENTARIO_INTERNO) {
            return (internoExternalEnabled || inventarioExternalEnabled) && obtenerUrlInventarioUnificado().isPresent();
        }
        if (origen == OrigenDatos.INVENTARIO_OFICIAL) {
            return (oficialExternalEnabled || inventarioExternalEnabled) && obtenerUrlInventarioUnificado().isPresent();
        }
        if (origen == OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS) {
            return (desagregadosExternalEnabled || inventarioExternalEnabled) && obtenerUrlInventarioUnificado().isPresent();
        }
        if (origen == OrigenDatos.CENTROS_TRABAJO_CCT) {
            return cctExternalEnabled && cctJdbcUrl.isPresent() && !cctJdbcUrl.get().isBlank();
        }
        if (origen == OrigenDatos.PERSONAL_RH) {
            return rhExternalEnabled && rhJdbcUrl.isPresent() && !rhJdbcUrl.get().isBlank();
        }
        if (origen == OrigenDatos.ESTADISTICA) {
            return estadisticaExternalEnabled && estadisticaJdbcUrl.isPresent() && !estadisticaJdbcUrl.get().isBlank();
        }
        if (origen == OrigenDatos.CONTROL_RIESGO) {
            return riesgoExternalEnabled && riesgoJdbcUrl.isPresent() && !riesgoJdbcUrl.get().isBlank();
        }
        return false;
    }

    private Optional<String> obtenerUrlInventarioUnificado() {
        if (inventarioJdbcUrl != null && inventarioJdbcUrl.isPresent() && !inventarioJdbcUrl.get().isBlank()) {
            return inventarioJdbcUrl;
        }
        if (internoJdbcUrl != null && internoJdbcUrl.isPresent() && !internoJdbcUrl.get().isBlank()) {
            return internoJdbcUrl;
        }
        if (oficialJdbcUrl != null && oficialJdbcUrl.isPresent() && !oficialJdbcUrl.get().isBlank()) {
            return oficialJdbcUrl;
        }
        if (desagregadosJdbcUrl != null && desagregadosJdbcUrl.isPresent() && !desagregadosJdbcUrl.get().isBlank()) {
            return desagregadosJdbcUrl;
        }
        return Optional.empty();
    }

    private Connection abrirConexionConFallbackEsquemaInventario(String urlPrimaria, String user, String pass) throws SQLException {
        DriverManager.setLoginTimeout(4);
        List<String> urlsCandidatas = new ArrayList<>();
        urlsCandidatas.add(urlPrimaria.trim());

        String baseHost = "jdbc:postgresql://192.168.13.100:5432/";
        if (urlPrimaria.contains("192.168.13.100:5432")) {
            String params = "?currentSchema=Inventario,inventario,public";
            String urlPostgres = baseHost + "postgres" + params;
            String urlInventarioMayus = baseHost + "Inventario" + params;
            String urlInventarioMinus = baseHost + "inventario" + params;
            if (!urlsCandidatas.contains(urlPostgres)) urlsCandidatas.add(urlPostgres);
            if (!urlsCandidatas.contains(urlInventarioMayus)) urlsCandidatas.add(urlInventarioMayus);
            if (!urlsCandidatas.contains(urlInventarioMinus)) urlsCandidatas.add(urlInventarioMinus);
        }

        SQLException ultimoError = null;
        for (String urlIntento : urlsCandidatas) {
            try {
                Connection conn = DriverManager.getConnection(urlIntento, user, pass);
                try (Statement st = conn.createStatement()) {
                    st.execute("SET search_path TO \"Inventario\", inventario, public;");
                } catch (SQLException ignored) {
                }
                return conn;
            } catch (SQLException e) {
                ultimoError = e;
            }
        }

        if (defaultDataSource != null) {
            return defaultDataSource.getConnection();
        }
        throw ultimoError != null ? ultimoError : new SQLException("No se pudo conectar al origen de datos solicitado.");
    }

    /**
     * Obtiene una conexión SQL para el dominio solicitado.
     * Unifica INVENTARIOS, INVENTARIO_INTERNO, INVENTARIO_OFICIAL e INVENTARIO_OFICIAL_DESAGREGADOS
     * hacia la BD de Inventario en 192.168.13.100:5432 con search_path a "Inventario" y fallback transparente.
     */
    public Connection obtenerConexion(OrigenDatos origen) throws SQLException {
        if (origen == OrigenDatos.INVENTARIOS
                || origen == OrigenDatos.INVENTARIO_INTERNO
                || origen == OrigenDatos.INVENTARIO_OFICIAL
                || origen == OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS) {
            if (tieneConexionExternaActiva(origen)) {
                String url = obtenerUrlInventarioUnificado().orElse("jdbc:postgresql://192.168.13.100:5432/postgres?currentSchema=Inventario,inventario,public");
                String user = inventarioUsername.or(() -> internoUsername).or(() -> oficialUsername).or(() -> desagregadosUsername).orElse("postgres");
                String pass = inventarioPassword.or(() -> internoPassword).or(() -> oficialPassword).or(() -> desagregadosPassword).orElse("S1st3m4s2022#");
                return abrirConexionConFallbackEsquemaInventario(url, user, pass);
            }
        }
        if (origen == OrigenDatos.CENTROS_TRABAJO_CCT && cctExternalEnabled && cctJdbcUrl.isPresent() && !cctJdbcUrl.get().isBlank()) {
            return abrirConexionConFallbackEsquemaInventario(
                    cctJdbcUrl.get().trim(),
                    cctUsername.orElse("postgres"),
                    cctPassword.orElse("S1st3m4s2022#")
            );
        }
        if (origen == OrigenDatos.PERSONAL_RH && rhExternalEnabled && rhJdbcUrl.isPresent() && !rhJdbcUrl.get().isBlank()) {
            return abrirConexionConFallbackEsquemaInventario(
                    rhJdbcUrl.get().trim(),
                    rhUsername.orElse("postgres"),
                    rhPassword.orElse("S1st3m4s2022#")
            );
        }
        if (origen == OrigenDatos.ESTADISTICA && estadisticaExternalEnabled && estadisticaJdbcUrl.isPresent() && !estadisticaJdbcUrl.get().isBlank()) {
            return abrirConexionConFallbackEsquemaInventario(
                    estadisticaJdbcUrl.get().trim(),
                    estadisticaUsername.orElse("postgres"),
                    estadisticaPassword.orElse("S1st3m4s2022#")
            );
        }
        if (origen == OrigenDatos.CONTROL_RIESGO && riesgoExternalEnabled && riesgoJdbcUrl.isPresent() && !riesgoJdbcUrl.get().isBlank()) {
            return abrirConexionConFallbackEsquemaInventario(
                    riesgoJdbcUrl.get().trim(),
                    riesgoUsername.orElse("postgres"),
                    riesgoPassword.orElse("S1st3m4s2022#")
            );
        }
        return defaultDataSource.getConnection();
    }

    @GET
    @RolesAllowed("SUPERADMIN")
    public Response verificarEstadoOrigenes() {
        Map<String, EstadoOrigenDTO> estado = new LinkedHashMap<>();
        estado.put("inventarios", evaluarOrigen(OrigenDatos.INVENTARIOS, inventarioExternalEnabled));
        estado.put("inventarioInterno", evaluarOrigen(OrigenDatos.INVENTARIO_INTERNO, internoExternalEnabled || inventarioExternalEnabled));
        estado.put("inventarioOficial", evaluarOrigen(OrigenDatos.INVENTARIO_OFICIAL, oficialExternalEnabled || inventarioExternalEnabled));
        estado.put("inventarioOficialDesagregados", evaluarOrigen(OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS, desagregadosExternalEnabled || inventarioExternalEnabled));
        estado.put("acervoHistorico", evaluarOrigen(OrigenDatos.ACERVO_HISTORICO, false));
        estado.put("centrosTrabajoCCT", evaluarOrigen(OrigenDatos.CENTROS_TRABAJO_CCT, cctExternalEnabled));
        estado.put("personalRH", evaluarOrigen(OrigenDatos.PERSONAL_RH, rhExternalEnabled));
        estado.put("estadistica", evaluarOrigen(OrigenDatos.ESTADISTICA, estadisticaExternalEnabled));
        estado.put("controlRiesgo", evaluarOrigen(OrigenDatos.CONTROL_RIESGO, riesgoExternalEnabled));
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
