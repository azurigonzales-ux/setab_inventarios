package org.acme;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Adaptador CRUD relacional para operar directamente sobre las 9 tablas del
 * Diagrama Entidad-Relación en PostgreSQL (192.168.13.100) cuando las fuentes
 * externas (INTERNO, OFICIAL, OFICIAL_DESEGREGADOS, CCT, RH) tienen enabled=true.
 */
@ApplicationScoped
public class PostgresRelacionalCrudService {

    @Inject
    MultiDataSourceConfig multiDataSourceConfig;

    public static class TablaMeta {
        public String schemaReal;
        public String nombreReal;
        public String pkColumna;
        public boolean pkAutoincremental;
        public Map<String, Integer> longitudColumnas = new HashMap<>();
        public Map<String, Integer> tipoSqlColumnas = new HashMap<>();
        public Set<String> columnas = new LinkedHashSet<>();
        public Map<String, String> nombreRealColumna = new HashMap<>();
        public Map<String, String> fkPorTablaDestino = new HashMap<>();
    }

    private static final long TTL_CACHE_MS = 10 * 60 * 1000L;

    private static class CacheEntry<T> {
        final T valor;
        final long expiraEn;

        CacheEntry(T valor, long ttlMs) {
            this.valor = valor;
            this.expiraEn = System.currentTimeMillis() + ttlMs;
        }

        boolean vigente() {
            return System.currentTimeMillis() < expiraEn;
        }
    }

    private final Map<String, TablaMeta> cacheMetadatos = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Boolean>> cacheDisponibilidadTablas = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Map<Integer, String>>> cacheMapasSimples = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<CatalogoGeneral>>> cacheCatalogoPorTipo = new ConcurrentHashMap<>();
    private volatile CacheEntry<List<CentroTrabajo>> cacheCentrosTrabajo = null;
    private volatile CacheEntry<Map<Integer, CentroTrabajo>> cacheCctPorId = null;
    private volatile CacheEntry<List<Empleado>> cacheResponsables = null;
    private volatile CacheEntry<Map<String, Empleado>> cacheIndiceDirectores = null;
    private volatile CacheEntry<List<CatalogoGeneral>> cacheTodosCatalogos = null;
    private volatile CacheEntry<Map<String, String>> cacheMapaCctPorResponsable = null;

    public MultiDataSourceConfig.OrigenDatos resolverOrigenInventarioActivo(String fondo) {
        if (fondo != null && !fondo.isBlank()) {
            MultiDataSourceConfig.OrigenDatos especifico = multiDataSourceConfig.resolverOrigenPorFondo(fondo);
            return multiDataSourceConfig.tieneConexionExternaActiva(especifico) ? especifico : null;
        }
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.INVENTARIOS)) {
            return MultiDataSourceConfig.OrigenDatos.INVENTARIOS;
        }
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.INVENTARIO_INTERNO)) {
            return MultiDataSourceConfig.OrigenDatos.INVENTARIO_INTERNO;
        }
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.INVENTARIO_OFICIAL)) {
            return MultiDataSourceConfig.OrigenDatos.INVENTARIO_OFICIAL;
        }
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS)) {
            return MultiDataSourceConfig.OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS;
        }
        return null;
    }

    public MultiDataSourceConfig.OrigenDatos resolverOrigenCCTActivo() {
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.CENTROS_TRABAJO_CCT)) {
            return MultiDataSourceConfig.OrigenDatos.CENTROS_TRABAJO_CCT;
        }
        return resolverOrigenInventarioActivo(null);
    }

    public MultiDataSourceConfig.OrigenDatos resolverOrigenResponsablesActivo() {
        if (multiDataSourceConfig.tieneConexionExternaActiva(MultiDataSourceConfig.OrigenDatos.PERSONAL_RH)) {
            return MultiDataSourceConfig.OrigenDatos.PERSONAL_RH;
        }
        return resolverOrigenInventarioActivo(null);
    }

    public boolean puedeOperarInventario(String fondo) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(fondo);
        if (origen == null) {
            return false;
        }
        String key = "INV::" + origen.name();
        CacheEntry<Boolean> cached = cacheDisponibilidadTablas.get(key);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            boolean ok = inspeccionarTabla(conn, origen, "inventario") != null;
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(ok, TTL_CACHE_MS));
            return ok;
        } catch (Exception e) {
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(false, 30_000L));
            return false;
        }
    }

    public boolean puedeOperarCCT() {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        if (origen == null) {
            return false;
        }
        String key = "CCT::" + origen.name();
        CacheEntry<Boolean> cached = cacheDisponibilidadTablas.get(key);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            boolean ok = inspeccionarTabla(conn, origen, "cct") != null;
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(ok, TTL_CACHE_MS));
            return ok;
        } catch (Exception e) {
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(false, 30_000L));
            return false;
        }
    }

    public boolean puedeOperarResponsables() {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        if (origen == null) {
            return false;
        }
        String key = "RESP::" + origen.name();
        CacheEntry<Boolean> cached = cacheDisponibilidadTablas.get(key);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            boolean ok = inspeccionarTabla(conn, origen, "responsables") != null;
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(ok, TTL_CACHE_MS));
            return ok;
        } catch (Exception e) {
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(false, 30_000L));
            return false;
        }
    }

    public boolean puedeOperarCatalogo(String tipoCatalogo) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return false;
        }
        String[] candidatos = candidatosTablaCatalogo(tipoCatalogo);
        if (candidatos.length == 0) {
            return false;
        }
        String key = "CAT::" + origen.name() + "::" + tipoCatalogo.trim().toLowerCase(Locale.ROOT);
        CacheEntry<Boolean> cached = cacheDisponibilidadTablas.get(key);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            boolean ok = inspeccionarTabla(conn, origen, candidatos) != null;
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(ok, TTL_CACHE_MS));
            return ok;
        } catch (Exception e) {
            cacheDisponibilidadTablas.put(key, new CacheEntry<>(false, 30_000L));
            return false;
        }
    }

    private String[] candidatosTablaCatalogo(String tipoCatalogo) {
        if (tipoCatalogo == null) {
            return new String[0];
        }
        return switch (tipoCatalogo.trim().toLowerCase(Locale.ROOT)) {
            case "proveedores" -> new String[]{"proveedores"};
            case "partidas" -> new String[]{"partidas"};
            case "tipos_articulos", "tipos" -> new String[]{"tipos"};
            case "naturaleza_gasto", "naturaleza_gastos", "naturaleza_gastos_1" -> new String[]{"naturaleza_gastos_1", "naturaleza_gastos"};
            case "marcas" -> new String[]{"marcas"};
            case "tipos_folios" -> new String[]{"tipos_folios"};
            case "departamentos" -> new String[]{"departamentos"};
            case "unidades", "unidades_1" -> new String[]{"unidades_1", "unidades"};
            case "subdirecciones", "subdirector", "subdirector_1" -> new String[]{"subdirector_1", "subdirector"};
            case "giros" -> new String[]{"giros"};
            case "municipios", "municipio" -> new String[]{"municipio"};
            case "localidades", "localidad" -> new String[]{"localidad"};
            case "vialidades", "validades" -> new String[]{"validades"};
            case "asentamientos" -> new String[]{"asentamientos"};
            default -> new String[0];
        };
    }

    public TablaMeta inspeccionarTabla(Connection conn, MultiDataSourceConfig.OrigenDatos origen, String... posiblesNombres) throws SQLException {
        String[] esquemasBusqueda = new String[]{"inventario", "Inventario", "public", null};
        for (String candidato : posiblesNombres) {
            String cacheKey = origen.name() + "::" + candidato.toLowerCase(Locale.ROOT);
            if (cacheMetadatos.containsKey(cacheKey)) {
                return cacheMetadatos.get(cacheKey);
            }
            DatabaseMetaData md = conn.getMetaData();
            String nombreEncontrado = null;
            String schemaEncontrado = null;

            for (String esquema : esquemasBusqueda) {
                try (ResultSet rsTablas = md.getTables(null, esquema, candidato.toLowerCase(Locale.ROOT), new String[]{"TABLE", "VIEW"})) {
                    if (rsTablas.next()) {
                        nombreEncontrado = rsTablas.getString("TABLE_NAME");
                        schemaEncontrado = rsTablas.getString("TABLE_SCHEM");
                        if (schemaEncontrado == null) {
                            schemaEncontrado = esquema;
                        }
                        break;
                    }
                }
            }

            if (nombreEncontrado == null) {
                continue;
            }

            TablaMeta meta = new TablaMeta();
            meta.schemaReal = schemaEncontrado;
            meta.nombreReal = nombreEncontrado;

            try (ResultSet rsCols = md.getColumns(null, meta.schemaReal, nombreEncontrado, null)) {
                while (rsCols.next()) {
                    String rawColName = rsCols.getString("COLUMN_NAME");
                    String colName = rawColName.toLowerCase(Locale.ROOT);
                    String trimmedCol = colName.trim();
                    int colSize = rsCols.getInt("COLUMN_SIZE");
                    int sqlType = rsCols.getInt("DATA_TYPE");
                    String isAuto = rsCols.getString("IS_AUTOINCREMENT");
                    meta.columnas.add(colName);
                    meta.columnas.add(trimmedCol);
                    meta.nombreRealColumna.put(colName, rawColName);
                    meta.nombreRealColumna.put(trimmedCol, rawColName);
                    meta.longitudColumnas.put(colName, colSize);
                    meta.longitudColumnas.put(trimmedCol, colSize);
                    meta.tipoSqlColumnas.put(colName, sqlType);
                    meta.tipoSqlColumnas.put(trimmedCol, sqlType);
                    if ("YES".equalsIgnoreCase(isAuto) && meta.pkColumna == null) {
                        meta.pkColumna = trimmedCol;
                        meta.pkAutoincremental = true;
                    }
                }
            }

            meta.pkColumna = deducirPkPorConvencion(nombreEncontrado, meta.columnas);
            if (meta.pkColumna == null) {
                try (ResultSet rsPk = md.getPrimaryKeys(null, meta.schemaReal, nombreEncontrado)) {
                    if (rsPk.next()) {
                        meta.pkColumna = rsPk.getString("COLUMN_NAME").toLowerCase(Locale.ROOT).trim();
                    }
                }
            }

            cacheMetadatos.put(cacheKey, meta);
            return meta;
        }
        return null;
    }

    private String deducirPkPorConvencion(String tabla, Set<String> cols) {
        String t = tabla.toLowerCase(Locale.ROOT);
        if ("partidas".equals(t) && cols.contains("id_partida")) return "id_partida";
        if ("cct".equals(t) && cols.contains("id_cct")) return "id_cct";
        if ("proveedores".equals(t) && cols.contains("id_proveedor")) return "id_proveedor";
        if ("responsables".equals(t)) {
            if (cols.contains("id_responsable")) return "id_responsable";
            if (cols.contains("id_responsables")) return "id_responsables";
            if (cols.contains("id")) return "id";
            if (cols.contains("rfc")) return "rfc";
        }
        if ("marcas".equals(t) && cols.contains("id_marca")) return "id_marca";
        if ((t.startsWith("naturaleza_gasto")) && cols.contains("id_naturaleza_gastos")) return "id_naturaleza_gastos";
        if ("tipos_folios".equals(t) && cols.contains("tipo_folio_id")) return "tipo_folio_id";
        if ("tipos".equals(t) && cols.contains("id_tipo")) return "id_tipo";
        if ("departamentos".equals(t) && cols.contains("id_departamentos")) return "id_departamentos";
        if ((t.startsWith("unidades")) && cols.contains("id_unidades_1")) return "id_unidades_1";
        if ((t.startsWith("unidades")) && cols.contains("id_unidades")) return "id_unidades";
        if ((t.startsWith("subdirector")) && cols.contains("id_subdirector_1")) return "id_subdirector_1";
        if ((t.startsWith("subdirector")) && cols.contains("id_subdirector")) return "id_subdirector";
        if ("giros".equals(t) && cols.contains("id_giros_1")) return "id_giros_1";
        if ("municipio".equals(t) && cols.contains("id_municipio_1")) return "id_municipio_1";
        if ("localidad".equals(t) && cols.contains("id_localidad_1")) return "id_localidad_1";
        if ("validades".equals(t) && cols.contains("id_validades_1")) return "id_validades_1";
        if ("asentamientos".equals(t) && cols.contains("id_asentamiento")) return "id_asentamiento";
        if ("inventario".equals(t)) {
            if (cols.contains("id_inventario")) return "id_inventario";
            if (cols.contains("id")) return "id";
            if (cols.contains("invofm")) return "invofm";
        }
        for (String c : cols) {
            if (c.startsWith("id_") || c.endsWith("_id") || "id".equals(c)) {
                return c;
            }
        }
        return cols.isEmpty() ? "id" : cols.iterator().next();
    }

    private String truncarSegunMeta(TablaMeta meta, String columna, String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        Integer maxLen = meta.longitudColumnas.get(columna.toLowerCase(Locale.ROOT));
        if (maxLen != null && maxLen > 0 && maxLen < 10000 && limpio.length() > maxLen) {
            return limpio.substring(0, maxLen);
        }
        return limpio;
    }

    private String mapearEstadoFisicoACodigo(String estadoFisico) {
        if (estadoFisico == null || estadoFisico.isBlank()) {
            return "B";
        }
        String u = estadoFisico.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("N")) return "N";
        if (u.startsWith("B")) return "B";
        if (u.startsWith("R")) return "R";
        if (u.startsWith("M")) return "M";
        return u.substring(0, 1);
    }

    private String mapearCodigoAEstadoFisico(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return "BUENO";
        }
        return switch (codigo.trim().toUpperCase(Locale.ROOT)) {
            case "N", "NUEVO" -> "NUEVO";
            case "B", "BUENO", "1" -> "BUENO";
            case "R", "REGULAR", "2" -> "REGULAR";
            case "M", "MALO", "3" -> "MALO";
            default -> codigo.trim().toUpperCase(Locale.ROOT);
        };
    }

    // =========================================================================
    // 1. CRUD DE INVENTARIO (<-> Articulo)
    // =========================================================================

    public ArticuloResource.PaginacionResponse listarArticulos(
            String busqueda,
            String indiceBusqueda,
            String fondo,
            String categoria,
            String area,
            Integer page,
            Integer size
    ) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(fondo);
        if (origen == null) {
            throw new SQLException("No hay origen externo activo para inventario");
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                throw new SQLException("Tabla inventario no existe en origen " + origen);
            }

            List<Object> params = new ArrayList<>();
            StringBuilder where = new StringBuilder(" WHERE 1=1 ");

            if (busqueda != null && !busqueda.isBlank()) {
                String term = "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
                String ind = indiceBusqueda != null ? indiceBusqueda.trim().toUpperCase(Locale.ROOT) : "TODOS";
                List<String> colsBusqueda = new ArrayList<>();
                boolean incluirSubqueryCctResp = false;
                boolean incluirSubqueryPartidaTipo = false;

                if ("INVENTARIO".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "invofm", "inv_ant", "folio");
                } else if ("SERIE".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "serie");
                } else if ("CCT_RESGUARDANTE".equals(ind) || "RESGUARDANTE_CCT".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "cversp_act", "docto", "folio");
                    incluirSubqueryCctResp = true;
                } else if ("TIPO_PARTIDA".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "partida", "natgasto", "tipo_fin");
                    incluirSubqueryPartidaTipo = true;
                } else {
                    agregarSiExiste(metaInv, colsBusqueda,
                            "invofm", "inv_ant", "descripcion", "descripcion1", "marca", "modelo",
                            "serie", "proveedor", "factura", "dactura", "requis", "requisito",
                            "fol_fiscal", "folio_fiscal", "partida", "cversp_act", "folio");
                    incluirSubqueryCctResp = true;
                    incluirSubqueryPartidaTipo = true;
                }

                List<String> condicionesOr = new ArrayList<>();
                for (String col : colsBusqueda) {
                    condicionesOr.add("LOWER(COALESCE(CAST(" + col + " AS TEXT), '')) LIKE ?");
                    params.add(term);
                }

                if (incluirSubqueryCctResp) {
                    String colCct = metaInv.columnas.contains("id_cct") ? "id_cct" : (metaInv.columnas.contains("fk_cct") ? "fk_cct" : null);
                    TablaMeta metaCct = colCct != null ? inspeccionarTabla(conn, origen, "cct") : null;
                    if (colCct != null && metaCct != null) {
                        condicionesOr.add(colCct + " IN (SELECT id_cct FROM " + metaCct.nombreReal
                                + " WHERE LOWER(COALESCE(clave_ct, '')) LIKE ? OR LOWER(COALESCE(nombre_ct, '')) LIKE ? OR LOWER(COALESCE(director, '')) LIKE ?)");
                        params.add(term);
                        params.add(term);
                        params.add(term);
                    }
                    String colResp = metaInv.columnas.contains("id_responsable") ? "id_responsable" : (metaInv.columnas.contains("fk_responsable") ? "fk_responsable" : null);
                    TablaMeta metaResp = colResp != null ? inspeccionarTabla(conn, origen, "responsables") : null;
                    if (colResp != null && metaResp != null && metaResp.pkColumna != null) {
                        condicionesOr.add(colResp + " IN (SELECT " + metaResp.pkColumna + " FROM " + metaResp.nombreReal
                                + " WHERE LOWER(COALESCE(rfc, '')) LIKE ? OR LOWER(COALESCE(nombres, '')) LIKE ? OR LOWER(COALESCE(paterno, '')) LIKE ? OR LOWER(COALESCE(materno, '')) LIKE ? OR LOWER(COALESCE(apellidos, '')) LIKE ?)");
                        params.add(term);
                        params.add(term);
                        params.add(term);
                        params.add(term);
                        params.add(term);
                    }
                }

                if (incluirSubqueryPartidaTipo) {
                    String colPart = metaInv.columnas.contains("id_partida") ? "id_partida" : (metaInv.columnas.contains("fk_partida") ? "fk_partida" : null);
                    TablaMeta metaPart = colPart != null ? inspeccionarTabla(conn, origen, "partidas") : null;
                    if (colPart != null && metaPart != null) {
                        condicionesOr.add(colPart + " IN (SELECT id_partida FROM " + metaPart.nombreReal
                                + " WHERE LOWER(COALESCE(pa_partida, '')) LIKE ? OR LOWER(COALESCE(pa_descripcion, '')) LIKE ?)");
                        params.add(term);
                        params.add(term);
                    }
                    String colTipo = metaInv.columnas.contains("id_tipo") ? "id_tipo" : (metaInv.columnas.contains("fk_tipo") ? "fk_tipo" : null);
                    TablaMeta metaTipo = colTipo != null ? inspeccionarTabla(conn, origen, "tipos") : null;
                    if (colTipo != null && metaTipo != null) {
                        condicionesOr.add(colTipo + " IN (SELECT id_tipo FROM " + metaTipo.nombreReal
                                + " WHERE LOWER(COALESCE(tipos, '')) LIKE ? OR LOWER(COALESCE(descripcion, '')) LIKE ?)");
                        params.add(term);
                        params.add(term);
                    }
                }

                if (!condicionesOr.isEmpty()) {
                    where.append(" AND (").append(String.join(" OR ", condicionesOr)).append(")");
                }
            }

            if (fondo != null && !fondo.isBlank() && metaInv.columnas.contains("origen")) {
                String fondoNorm = fondo.trim().toUpperCase(Locale.ROOT);
                if ("INTERNO".equals(fondoNorm) || "OFICIAL".equals(fondoNorm)
                        || "OFICIAL_DESEGREGADOS".equals(fondoNorm) || "OFICIAL_DESAGREGADOS".equals(fondoNorm)) {
                    where.append(" AND (UPPER(TRIM(COALESCE(origen, ''))) = ? OR origen IS NULL OR TRIM(origen) = '')");
                    params.add(fondoNorm);
                } else {
                    where.append(" AND UPPER(TRIM(COALESCE(origen, ''))) = ?");
                    params.add(fondoNorm);
                }
            }

            if (categoria != null && !categoria.isBlank()) {
                String catNorm = categoria.trim().toUpperCase(Locale.ROOT);
                String colCct = metaInv.columnas.contains("id_cct") ? "id_cct" : (metaInv.columnas.contains("fk_cct") ? "fk_cct" : null);
                String colResp = metaInv.columnas.contains("id_responsable") ? "id_responsable" : (metaInv.columnas.contains("fk_responsable") ? "fk_responsable" : null);
                if ("EN_RESGUARDO".equals(catNorm) || "EN RESGUARDO CCT".equals(catNorm)) {
                    List<String> conds = new ArrayList<>();
                    if (colCct != null) conds.add("(" + colCct + " IS NOT NULL AND " + colCct + " > 0)");
                    if (colResp != null) conds.add("(" + colResp + " IS NOT NULL AND " + colResp + " > 0)");
                    if (metaInv.columnas.contains("cversp_act")) conds.add("(cversp_act IS NOT NULL AND TRIM(cversp_act) <> '')");
                    if (!conds.isEmpty()) {
                        where.append(" AND (").append(String.join(" OR ", conds)).append(")");
                    }
                    if (metaInv.columnas.contains("tipofolio")) {
                        where.append(" AND UPPER(TRIM(COALESCE(tipofolio, ''))) <> 'BAJ'");
                    }
                } else if ("DISPONIBLE".equals(catNorm) || "DISPONIBLE EN ALMACÉN".equals(catNorm) || "ALMACEN".equals(catNorm)) {
                    if (colCct != null) where.append(" AND (").append(colCct).append(" IS NULL OR ").append(colCct).append(" = 0)");
                    if (colResp != null) where.append(" AND (").append(colResp).append(" IS NULL OR ").append(colResp).append(" = 0)");
                    if (metaInv.columnas.contains("cversp_act")) where.append(" AND (cversp_act IS NULL OR TRIM(cversp_act) = '')");
                    if (metaInv.columnas.contains("tipofolio")) where.append(" AND UPPER(TRIM(COALESCE(tipofolio, ''))) <> 'BAJ'");
                } else if ("BAJA".equals(catNorm) || "BAJ".equals(catNorm)) {
                    List<String> condBaja = new ArrayList<>();
                    if (metaInv.columnas.contains("tipofolio")) condBaja.add("UPPER(TRIM(COALESCE(tipofolio, ''))) = 'BAJ'");
                    if (metaInv.columnas.contains("fechabaja")) condBaja.add("fechabaja IS NOT NULL");
                    if (metaInv.columnas.contains("ofibaja")) condBaja.add("(ofibaja IS NOT NULL AND TRIM(ofibaja) <> '')");
                    if (!condBaja.isEmpty()) {
                        where.append(" AND (").append(String.join(" OR ", condBaja)).append(")");
                    }
                } else if ("VEHICULOS".equals(catNorm) && metaInv.columnas.contains("placa")) {
                    where.append(" AND placa IS NOT NULL AND TRIM(placa) <> ''");
                } else if ("MOBILIARIO".equals(catNorm) && metaInv.columnas.contains("placa")) {
                    where.append(" AND (placa IS NULL OR TRIM(placa) = '')");
                }
            }

            if (area != null && !area.isBlank()) {
                String areaNorm = area.trim().toUpperCase(Locale.ROOT);
                if (metaInv.columnas.contains("estado") && ("NUEVO".equals(areaNorm) || "BUENO".equals(areaNorm) || "B".equals(areaNorm))) {
                    where.append(" AND UPPER(TRIM(COALESCE(estado, 'B'))) IN ('B', 'BUENO', 'N', 'NUEVO', '1', '')");
                } else if (metaInv.columnas.contains("estado") && ("REGULAR".equals(areaNorm) || "R".equals(areaNorm))) {
                    where.append(" AND UPPER(TRIM(COALESCE(estado, ''))) IN ('R', 'REGULAR', '2')");
                } else if (metaInv.columnas.contains("estado") && ("MALO".equals(areaNorm) || "M".equals(areaNorm))) {
                    where.append(" AND UPPER(TRIM(COALESCE(estado, ''))) IN ('M', 'MALO', '3')");
                } else if ("ALMACENGENERAL".equals(areaNorm)) {
                    String colCct = metaInv.columnas.contains("id_cct") ? "id_cct" : (metaInv.columnas.contains("fk_cct") ? "fk_cct" : null);
                    if (colCct != null) {
                        where.append(" AND (").append(colCct).append(" IS NULL OR ").append(colCct).append(" = 0)");
                    }
                }
            }

            long total = 0;
            String countSql = "SELECT COUNT(*) FROM " + metaInv.nombreReal + where;
            try (PreparedStatement psCount = conn.prepareStatement(countSql)) {
                for (int i = 0; i < params.size(); i++) {
                    psCount.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = psCount.executeQuery()) {
                    if (rs.next()) {
                        total = rs.getLong(1);
                    }
                }
            }

            int limit = (size != null && size > 0) ? size : 50;
            int offset = (page != null && page >= 0) ? page * limit : 0;

            String orderCol = metaInv.pkColumna != null ? metaInv.pkColumna : (metaInv.columnas.contains("invofm") ? "invofm" : "1");
            String selectSql = "SELECT * FROM " + metaInv.nombreReal + where + " ORDER BY " + orderCol + " DESC LIMIT ? OFFSET ?";

            Map<Integer, String> mapaCct = cargarMapaSimple(conn, origen, "cct", "id_cct", "clave_ct");
            Map<Integer, String> mapaPartidas = cargarMapaSimple(conn, origen, "partidas", "id_partida", "pa_partida");
            Map<Integer, String> mapaMarcas = cargarMapaSimple(conn, origen, "marcas", "id_marca", "descripcion");
            Map<Integer, String> mapaProveedores = cargarMapaSimple(conn, origen, "proveedores", "id_proveedor", "nombre");
            Map<Integer, String> mapaTipos = cargarMapaSimple(conn, origen, "tipos", "id_tipo", "descripcion");
            Map<Integer, String> mapaNatGasto = cargarMapaSimple(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", "descripcion");
            Map<Integer, String> mapaTiposFolios = cargarMapaSimple(conn, origen, "tipos_folios", "tipo_folio_id", "clave");

            List<Articulo> lista = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                int idx = 1;
                for (Object p : params) {
                    ps.setObject(idx++, p);
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    long rowCounter = offset + 1L;
                    while (rs.next()) {
                        Articulo art = mapearResultSetAArticulo(
                                rs, metaInv, origen, rowCounter++,
                                mapaCct, mapaPartidas, mapaMarcas, mapaProveedores, mapaTipos, mapaNatGasto, mapaTiposFolios
                        );
                        lista.add(art);
                    }
                }
            }

            enriquecerConMetadatosPanache(lista);

            ArticuloResource.PaginacionResponse resp = new ArticuloResource.PaginacionResponse();
            resp.articulos = lista;
            resp.totalRegistros = total;
            resp.pageCount = (int) Math.max(1, Math.ceil((double) total / limit));
            return resp;
        }
    }

    public List<Articulo> listarBienesPorResponsableOCct(Long idEmpleado, String rfc, String cctClave, String nombreResp) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return Collections.emptyList();
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                return Collections.emptyList();
            }

            List<String> ors = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            String colResp = metaInv.columnas.contains("id_responsable") ? "id_responsable" : (metaInv.columnas.contains("fk_responsable") ? "fk_responsable" : null);
            if (colResp != null && idEmpleado != null && idEmpleado > 0 && idEmpleado < 900_000L) {
                ors.add(colResp + " = ?");
                params.add(idEmpleado.intValue());
            }

            String colCct = metaInv.columnas.contains("id_cct") ? "id_cct" : (metaInv.columnas.contains("fk_cct") ? "fk_cct" : null);
            if (colCct != null && cctClave != null && !cctClave.isBlank() && !"SIN-CCT".equalsIgnoreCase(cctClave) && !"CENTRAL".equalsIgnoreCase(cctClave)) {
                TablaMeta metaCct = inspeccionarTabla(conn, origen, "cct");
                if (metaCct != null) {
                    ors.add(colCct + " IN (SELECT id_cct FROM " + metaCct.nombreReal + " WHERE UPPER(TRIM(COALESCE(clave_ct, ''))) = ?)");
                    params.add(cctClave.trim().toUpperCase(Locale.ROOT));
                }
            }

            if (metaInv.columnas.contains("cversp_act")) {
                if (rfc != null && !rfc.isBlank()) {
                    ors.add("UPPER(TRIM(COALESCE(cversp_act, ''))) = ?");
                    params.add(rfc.trim().toUpperCase(Locale.ROOT));
                }
                if (nombreResp != null && !nombreResp.isBlank()) {
                    ors.add("UPPER(TRIM(COALESCE(cversp_act, ''))) = ?");
                    params.add(nombreResp.trim().toUpperCase(Locale.ROOT));
                }
            }

            if (ors.isEmpty()) {
                return Collections.emptyList();
            }

            String orderCol = metaInv.pkColumna != null ? metaInv.pkColumna : (metaInv.columnas.contains("invofm") ? "invofm" : "1");
            String sql = "SELECT * FROM " + metaInv.nombreReal + " WHERE (" + String.join(" OR ", ors) + ") ORDER BY " + orderCol + " DESC LIMIT 500";

            Map<Integer, String> mapaCct = cargarMapaSimple(conn, origen, "cct", "id_cct", "clave_ct");
            Map<Integer, String> mapaPartidas = cargarMapaSimple(conn, origen, "partidas", "id_partida", "pa_partida");
            Map<Integer, String> mapaMarcas = cargarMapaSimple(conn, origen, "marcas", "id_marca", "descripcion");
            Map<Integer, String> mapaProveedores = cargarMapaSimple(conn, origen, "proveedores", "id_proveedor", "nombre");
            Map<Integer, String> mapaTipos = cargarMapaSimple(conn, origen, "tipos", "id_tipo", "descripcion");
            Map<Integer, String> mapaNatGasto = cargarMapaSimple(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", "descripcion");
            Map<Integer, String> mapaTiposFolios = cargarMapaSimple(conn, origen, "tipos_folios", "tipo_folio_id", "clave");

            List<Articulo> resultado = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    long rowCounter = 1L;
                    while (rs.next()) {
                        resultado.add(mapearResultSetAArticulo(
                                rs, metaInv, origen, rowCounter++,
                                mapaCct, mapaPartidas, mapaMarcas, mapaProveedores, mapaTipos, mapaNatGasto, mapaTiposFolios
                        ));
                    }
                }
            }
            enriquecerConMetadatosPanache(resultado);
            return resultado;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Articulo buscarPorFolio(String folio) throws SQLException {
        if (folio == null || folio.isBlank()) {
            return null;
        }
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return null;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                return null;
            }
            List<String> cols = new ArrayList<>();
            agregarSiExiste(metaInv, cols, "invofm", "inv_ant", "serie", "folio");
            if (cols.isEmpty()) {
                return null;
            }
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(metaInv.nombreReal).append(" WHERE ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("UPPER(TRIM(COALESCE(CAST(").append(cols.get(i)).append(" AS TEXT), ''))) = ?");
            }
            sql.append(" LIMIT 1");

            Map<Integer, String> mapaCct = cargarMapaSimple(conn, origen, "cct", "id_cct", "clave_ct");
            Map<Integer, String> mapaPartidas = cargarMapaSimple(conn, origen, "partidas", "id_partida", "pa_partida");
            Map<Integer, String> mapaMarcas = cargarMapaSimple(conn, origen, "marcas", "id_marca", "descripcion");
            Map<Integer, String> mapaProveedores = cargarMapaSimple(conn, origen, "proveedores", "id_proveedor", "nombre");
            Map<Integer, String> mapaTipos = cargarMapaSimple(conn, origen, "tipos", "id_tipo", "descripcion");
            Map<Integer, String> mapaNatGasto = cargarMapaSimple(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", "descripcion");
            Map<Integer, String> mapaTiposFolios = cargarMapaSimple(conn, origen, "tipos_folios", "tipo_folio_id", "clave");

            String f = folio.trim().toUpperCase(Locale.ROOT);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < cols.size(); i++) {
                    ps.setString(i + 1, f);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Articulo art = mapearResultSetAArticulo(
                                rs, metaInv, origen, 1L,
                                mapaCct, mapaPartidas, mapaMarcas, mapaProveedores, mapaTipos, mapaNatGasto, mapaTiposFolios
                        );
                        enriquecerConMetadatosPanache(Collections.singletonList(art));
                        return art;
                    }
                }
            }
            if (f.matches("\\d+")) {
                try {
                    return buscarPorIdRelacional(Long.parseLong(f));
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
    }

    public Articulo buscarPorIdRelacional(Long id) {
        if (id == null) {
            return null;
        }
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return null;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null || metaInv.pkColumna == null) {
                return null;
            }
            String sql = "SELECT * FROM " + metaInv.nombreReal + " WHERE " + metaInv.pkColumna + " = ? LIMIT 1";
            Map<Integer, String> mapaCct = cargarMapaSimple(conn, origen, "cct", "id_cct", "clave_ct");
            Map<Integer, String> mapaPartidas = cargarMapaSimple(conn, origen, "partidas", "id_partida", "pa_partida");
            Map<Integer, String> mapaMarcas = cargarMapaSimple(conn, origen, "marcas", "id_marca", "descripcion");
            Map<Integer, String> mapaProveedores = cargarMapaSimple(conn, origen, "proveedores", "id_proveedor", "nombre");
            Map<Integer, String> mapaTipos = cargarMapaSimple(conn, origen, "tipos", "id_tipo", "descripcion");
            Map<Integer, String> mapaNatGasto = cargarMapaSimple(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", "descripcion");
            Map<Integer, String> mapaTiposFolios = cargarMapaSimple(conn, origen, "tipos_folios", "tipo_folio_id", "clave");

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Articulo art = mapearResultSetAArticulo(
                                rs, metaInv, origen, id,
                                mapaCct, mapaPartidas, mapaMarcas, mapaProveedores, mapaTipos, mapaNatGasto, mapaTiposFolios
                        );
                        enriquecerConMetadatosPanache(Collections.singletonList(art));
                        return art;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void enriquecerConMetadatosPanache(List<Articulo> lista) {
        if (lista == null || lista.isEmpty()) {
            return;
        }
        try {
            List<Articulo> extendidos = Articulo.find(
                    "imagenBienBase64 is not null or estadoVerificacionAnual = ?1 or fechaUltimaActualizacionAnual is not null",
                    "VERIFICADO"
            ).list();
            if (extendidos == null || extendidos.isEmpty()) {
                return;
            }
            Map<String, Articulo> porNumInv = new HashMap<>();
            Map<Long, Articulo> porId = new HashMap<>();
            for (Articulo ext : extendidos) {
                if (ext == null) continue;
                if (ext.id != null) {
                    porId.put(ext.id, ext);
                }
                if (ext.numeroInventario != null && !ext.numeroInventario.isBlank()) {
                    porNumInv.put(ext.numeroInventario.trim().toUpperCase(Locale.ROOT), ext);
                }
            }
            for (Articulo a : lista) {
                if (a == null) continue;
                Articulo match = null;
                if (a.numeroInventario != null && !a.numeroInventario.isBlank()) {
                    match = porNumInv.get(a.numeroInventario.trim().toUpperCase(Locale.ROOT));
                }
                if (match == null && a.id != null) {
                    match = porId.get(a.id);
                }
                if (match != null) {
                    if (match.imagenBienBase64 != null && !match.imagenBienBase64.isBlank()) {
                        a.imagenBienBase64 = match.imagenBienBase64;
                    }
                    if (match.estadoVerificacionAnual != null && !match.estadoVerificacionAnual.isBlank()) {
                        a.estadoVerificacionAnual = match.estadoVerificacionAnual;
                    }
                    if (match.fechaUltimaActualizacionAnual != null) {
                        a.fechaUltimaActualizacionAnual = match.fechaUltimaActualizacionAnual;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void registrarBajaOLiberacionEnInventarioRelacional(String invofm, Long idInventario) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                return;
            }
            List<String> setsSql = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (metaInv.columnas.contains("id_cct")) setsSql.add("id_cct = NULL");
            if (metaInv.columnas.contains("fk_cct")) setsSql.add("fk_cct = NULL");
            if (metaInv.columnas.contains("id_responsable")) setsSql.add("id_responsable = NULL");
            if (metaInv.columnas.contains("fk_responsable")) setsSql.add("fk_responsable = NULL");
            if (metaInv.columnas.contains("cversp_act")) setsSql.add("cversp_act = NULL");
            if (metaInv.columnas.contains("tipofolio")) {
                setsSql.add("tipofolio = ?");
                params.add("BAJ");
            }
            if (metaInv.columnas.contains("fechabaja")) {
                setsSql.add("fechabaja = ?");
                params.add(java.sql.Date.valueOf(LocalDate.now()));
            } else if (metaInv.columnas.contains("fecbaja")) {
                setsSql.add("fecbaja = ?");
                params.add(java.sql.Date.valueOf(LocalDate.now()));
            }
            if (setsSql.isEmpty()) {
                return;
            }
            String baseUpdate = "UPDATE " + metaInv.nombreReal + " SET " + String.join(", ", setsSql);
            int updated = 0;
            if (idInventario != null && idInventario > 0 && metaInv.pkColumna != null) {
                try (PreparedStatement ps = conn.prepareStatement(baseUpdate + " WHERE " + metaInv.pkColumna + " = ?")) {
                    int idx = 1;
                    for (Object p : params) ps.setObject(idx++, p);
                    ps.setObject(idx, idInventario);
                    updated = ps.executeUpdate();
                }
            }
            if (updated == 0 && invofm != null && !invofm.isBlank() && metaInv.columnas.contains("invofm")) {
                try (PreparedStatement ps = conn.prepareStatement(baseUpdate + " WHERE UPPER(TRIM(COALESCE(invofm, ''))) = ?")) {
                    int idx = 1;
                    for (Object p : params) ps.setObject(idx++, p);
                    ps.setString(idx, invofm.trim().toUpperCase(Locale.ROOT));
                    ps.executeUpdate();
                }
            }
            cacheMapaCctPorResponsable = null;
            cacheResponsables = null;
            cacheIndiceDirectores = null;
        } catch (Exception ignored) {
        }
    }

    public void actualizarResguardoEnInventarioRelacional(
            String invofm,
            Long idInventario,
            String cctClave,
            Long idResponsable,
            String rfcOClaveResp,
            String folioResguardo,
            String tipoFolio
    ) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                return;
            }

            Map<String, Object> sets = new LinkedHashMap<>();

            if (cctClave != null && !cctClave.isBlank()
                    && !"SIN-CCT".equalsIgnoreCase(cctClave.trim())
                    && !"CENTRAL".equalsIgnoreCase(cctClave.trim())) {
                Integer fkCct = resolverFkEnTabla(conn, origen, "cct", "id_cct", new String[]{"clave_ct", "nombre_ct"}, cctClave.trim());
                if (fkCct != null) {
                    if (metaInv.columnas.contains("id_cct")) sets.put("id_cct", fkCct);
                    if (metaInv.columnas.contains("fk_cct")) sets.put("fk_cct", fkCct);
                }
            }

            Integer fkResp = null;
            if (idResponsable != null && idResponsable > 0 && idResponsable < 900_000L) {
                fkResp = idResponsable.intValue();
            } else if (rfcOClaveResp != null && !rfcOClaveResp.isBlank()) {
                TablaMeta metaResp = inspeccionarTabla(conn, origen, "responsables");
                if (metaResp != null && metaResp.pkColumna != null) {
                    fkResp = resolverFkEnTabla(
                            conn, origen, "responsables", metaResp.pkColumna,
                            new String[]{"rfc", "rfc2", "curp", "nombres"}, rfcOClaveResp.trim()
                    );
                }
            }
            if (fkResp != null) {
                if (metaInv.columnas.contains("id_responsable")) sets.put("id_responsable", fkResp);
                if (metaInv.columnas.contains("fk_responsable")) sets.put("fk_responsable", fkResp);
            }

            if (rfcOClaveResp != null && !rfcOClaveResp.isBlank()) {
                ponerTexto(metaInv, sets, "cversp_act", extraerClaveInicial(rfcOClaveResp));
            }
            if (folioResguardo != null && !folioResguardo.isBlank()) {
                ponerTexto(metaInv, sets, "folio", folioResguardo.trim());
            }
            if (tipoFolio != null && !tipoFolio.isBlank()) {
                ponerTexto(metaInv, sets, "tipofolio", tipoFolio.trim());
                Integer fkTf = resolverFkEnTabla(conn, origen, "tipos_folios", "tipo_folio_id", new String[]{"clave", "descripcion"}, tipoFolio.trim());
                if (fkTf != null) {
                    if (metaInv.columnas.contains("id_tipo_folio")) sets.put("id_tipo_folio", fkTf);
                    if (metaInv.columnas.contains("fk_tipofolio")) sets.put("fk_tipofolio", fkTf);
                }
            }
            ponerFecha(metaInv, sets, "fecres", LocalDate.now());

            if (sets.isEmpty()) {
                return;
            }

            List<String> cols = new ArrayList<>(sets.keySet());
            StringBuilder baseUpdate = new StringBuilder("UPDATE ").append(metaInv.nombreReal).append(" SET ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) baseUpdate.append(", ");
                baseUpdate.append(columnaSqlSegura(metaInv, cols.get(i))).append(" = ?");
            }

            int updated = 0;
            if (idInventario != null && idInventario > 0 && metaInv.pkColumna != null) {
                String sqlById = baseUpdate + " WHERE " + metaInv.pkColumna + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlById)) {
                    int idx = 1;
                    for (String col : cols) {
                        asignarParametroSql(ps, idx++, sets.get(col));
                    }
                    ps.setObject(idx, idInventario);
                    updated = ps.executeUpdate();
                }
            }

            if (updated == 0 && invofm != null && !invofm.isBlank() && metaInv.columnas.contains("invofm")) {
                String sqlByInv = baseUpdate + " WHERE UPPER(TRIM(COALESCE(invofm, ''))) = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlByInv)) {
                    int idx = 1;
                    for (String col : cols) {
                        asignarParametroSql(ps, idx++, sets.get(col));
                    }
                    ps.setString(idx, invofm.trim().toUpperCase(Locale.ROOT));
                    ps.executeUpdate();
                }
            }

            cacheMapaCctPorResponsable = null;
            cacheResponsables = null;
            cacheIndiceDirectores = null;
        } catch (Exception ignored) {
        }
    }

    public Articulo sincronizarArticuloEnPanache(Long idSolicitado, String numeroInventarioHint) {
        Articulo localPorId = idSolicitado != null ? Articulo.findById(idSolicitado) : null;
        Articulo ext = null;
        if (puedeOperarInventario(null)) {
            try {
                if (idSolicitado != null) {
                    ext = buscarPorIdRelacional(idSolicitado);
                }
                if (ext == null && numeroInventarioHint != null && !numeroInventarioHint.isBlank()) {
                    ext = buscarPorFolio(numeroInventarioHint.trim());
                }
            } catch (Exception ignored) {
            }
        }
        if (ext == null) {
            if (localPorId != null) {
                return localPorId;
            }
            if (numeroInventarioHint != null && !numeroInventarioHint.isBlank()) {
                return Articulo.find("upper(numeroInventario) = ?1", numeroInventarioHint.trim().toUpperCase(Locale.ROOT)).firstResult();
            }
            return null;
        }
        return sincronizarObjetoArticuloEnPanache(ext, localPorId);
    }

    public Articulo sincronizarObjetoArticuloEnPanache(Articulo ext, Articulo localPorId) {
        if (ext == null) {
            return localPorId;
        }
        if (localPorId != null && ext.numeroInventario != null && localPorId.numeroInventario != null
                && ext.numeroInventario.trim().equalsIgnoreCase(localPorId.numeroInventario.trim())) {
            return localPorId;
        }
        if (ext.numeroInventario != null && !ext.numeroInventario.isBlank()) {
            Articulo existentePorInv = Articulo.find(
                    "upper(numeroInventario) = ?1",
                    ext.numeroInventario.trim().toUpperCase(Locale.ROOT)
            ).firstResult();
            if (existentePorInv != null) {
                return existentePorInv;
            }
        } else if (localPorId != null) {
            return localPorId;
        }

        Articulo nuevo = new Articulo();
        nuevo.id = null;
        nuevo.numeroInventario = ext.numeroInventario != null ? ext.numeroInventario : ("REL-" + ext.id);
        nuevo.numeroInventarioAnterior = ext.numeroInventarioAnterior;
        nuevo.fondoOrigen = ext.fondoOrigen != null ? ext.fondoOrigen : "OFICIAL";
        nuevo.tokenQR = ext.tokenQR != null ? ext.tokenQR : UUID.randomUUID().toString();
        nuevo.tipoMaterial = ext.tipoMaterial;
        nuevo.subClave = ext.subClave;
        nuevo.cuentaContable = ext.cuentaContable;
        nuevo.descripcion = ext.descripcion;
        nuevo.descripcionSecundaria = ext.descripcionSecundaria;
        nuevo.marca = ext.marca;
        nuevo.modelo = ext.modelo;
        nuevo.numeroSerie = ext.numeroSerie;
        nuevo.color = ext.color;
        nuevo.categoria = ext.categoria;
        nuevo.areaUbicacion = ext.areaUbicacion;
        nuevo.unidadResponsable = ext.unidadResponsable;
        nuevo.subdireccion = ext.subdireccion;
        nuevo.departamento = ext.departamento;
        nuevo.centroTrabajo = ext.centroTrabajo;
        nuevo.resguardanteActual = ext.resguardanteActual;
        nuevo.resguardanteHistorico = ext.resguardanteHistorico;
        nuevo.folioResguardo = ext.folioResguardo;
        nuevo.tipoFolioResguardo = ext.tipoFolioResguardo;
        nuevo.documentoResguardo = ext.documentoResguardo;
        nuevo.fechaResguardo = ext.fechaResguardo;
        nuevo.cantidadStock = (ext.cantidadStock != null && ext.cantidadStock > 0) ? ext.cantidadStock : 1.0;
        nuevo.estadoFisico = ext.estadoFisico != null ? ext.estadoFisico : "BUENO";
        nuevo.estado = ext.estado != null ? ext.estado : "Activo";
        nuevo.proveedor = ext.proveedor;
        nuevo.factura = ext.factura;
        nuevo.folioFiscalUuid = ext.folioFiscalUuid;
        nuevo.boletaSalida = ext.boletaSalida;
        nuevo.fechaFactura = ext.fechaFactura;
        nuevo.fechaAdquisicion = ext.fechaAdquisicion != null ? ext.fechaAdquisicion : LocalDate.now();
        nuevo.precioSinIva = ext.precioSinIva;
        nuevo.valorInicial = ext.valorInicial;
        nuevo.pedido = ext.pedido;
        nuevo.requisicion = ext.requisicion;
        nuevo.naturalezaGasto = ext.naturalezaGasto;
        nuevo.partida = ext.partida;
        nuevo.motor = ext.motor;
        nuevo.placa = ext.placa;
        nuevo.poliza = ext.poliza;
        nuevo.vigenciaDesde = ext.vigenciaDesde;
        nuevo.vigenciaPoliza = ext.vigenciaPoliza;
        nuevo.tipoBaja = ext.tipoBaja;
        nuevo.carpetaInvestigacion = ext.carpetaInvestigacion;
        nuevo.oficioBaja = ext.oficioBaja;
        nuevo.fechaBaja = ext.fechaBaja;
        nuevo.numeroActa = ext.numeroActa;
        nuevo.fechaActa = ext.fechaActa;
        nuevo.imagenBienBase64 = ext.imagenBienBase64;
        nuevo.fechaUltimaActualizacionAnual = ext.fechaUltimaActualizacionAnual;
        nuevo.estadoVerificacionAnual = ext.estadoVerificacionAnual != null ? ext.estadoVerificacionAnual : "PENDIENTE";
        nuevo.persistAndFlush();
        return nuevo;
    }

    public Empleado sincronizarEmpleadoEnPanache(Long idEmpleadoSolicitado) {
        if (idEmpleadoSolicitado == null) {
            return null;
        }
        Empleado localPorId = Empleado.findById(idEmpleadoSolicitado);
        Empleado ext = null;
        if (puedeOperarResponsables()) {
            try {
                for (Empleado e : listarResponsables()) {
                    if (e != null && idEmpleadoSolicitado.equals(e.idEmpleado)) {
                        ext = e;
                        break;
                    }
                }
                if (ext == null) {
                    ext = buscarResponsablePorIdRelacional(idEmpleadoSolicitado);
                }
            } catch (Exception ignored) {
            }
        }
        if (ext == null) {
            return localPorId;
        }
        if (localPorId != null) {
            boolean coincideRfc = ext.rfc != null && !ext.rfc.isBlank()
                    && localPorId.rfc != null && ext.rfc.trim().equalsIgnoreCase(localPorId.rfc.trim());
            boolean coincideNombre = ext.nombre != null && !ext.nombre.isBlank()
                    && localPorId.nombre != null && ext.nombre.trim().equalsIgnoreCase(localPorId.nombre.trim());
            if (coincideRfc || coincideNombre) {
                if ((localPorId.cct == null || localPorId.cct.isBlank()) && ext.cct != null && !ext.cct.isBlank()) {
                    localPorId.cct = ext.cct;
                    localPorId.nombreEscuela = ext.nombreEscuela;
                    localPorId.persist();
                }
                return localPorId;
            }
        }

        Empleado existente = null;
        if (ext.rfc != null && !ext.rfc.isBlank()) {
            String rfcUp = ext.rfc.trim().toUpperCase(Locale.ROOT);
            existente = Empleado.find(
                    "upper(rfc) = ?1 or upper(usuario) = ?1 or upper(claveResponsable) = ?1",
                    rfcUp
            ).firstResult();
        }
        if (existente == null && ext.nombre != null && !ext.nombre.isBlank()) {
            String nomUp = ext.nombre.trim().toUpperCase(Locale.ROOT);
            if (ext.cct != null && !ext.cct.isBlank()) {
                existente = Empleado.find(
                        "upper(nombre) = ?1 and upper(cct) = ?2",
                        nomUp,
                        ext.cct.trim().toUpperCase(Locale.ROOT)
                ).firstResult();
            }
            if (existente == null) {
                existente = Empleado.find("upper(nombre) = ?1", nomUp).firstResult();
            }
        }
        if (existente != null) {
            if ((existente.cct == null || existente.cct.isBlank()) && ext.cct != null && !ext.cct.isBlank()) {
                existente.cct = ext.cct;
                existente.nombreEscuela = ext.nombreEscuela;
                existente.persist();
            }
            return existente;
        }

        Empleado nuevo = new Empleado();
        nuevo.idEmpleado = null;
        nuevo.nombre = ext.nombre;
        nuevo.numeroEmpleado = ext.numeroEmpleado;
        nuevo.rfc = ext.rfc;
        nuevo.cct = ext.cct;
        nuevo.nombreEscuela = ext.nombreEscuela;
        nuevo.usuario = ext.usuario;
        nuevo.cargo = ext.cargo;
        nuevo.area = ext.area;
        nuevo.curp = ext.curp;
        nuevo.claveResponsable = ext.claveResponsable;
        nuevo.apellidoPaterno = ext.apellidoPaterno;
        nuevo.apellidoMaterno = ext.apellidoMaterno;
        nuevo.activo = ext.activo != null ? ext.activo : 1;
        nuevo.persistAndFlush();
        return nuevo;
    }

    public Articulo insertarArticulo(Articulo articulo, String usuarioAdmin) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(articulo.fondoOrigen);
        if (origen == null) {
            throw new SQLException("No hay origen externo activo para registrar el artículo");
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                throw new SQLException("Tabla inventario no encontrada");
            }

            if (articulo.numeroInventario == null || articulo.numeroInventario.isBlank() || "AUTOGENERADO".equalsIgnoreCase(articulo.numeroInventario.trim())) {
                int anioCorto = LocalDate.now().getYear() % 100;
                long totalActual = contarFilas(conn, metaInv.nombreReal);
                articulo.numeroInventario = String.format("SE-AG-%02d-%05d", anioCorto, totalActual + 1);
            }
            if (articulo.tokenQR == null || articulo.tokenQR.isBlank()) {
                articulo.tokenQR = UUID.randomUUID().toString();
            }
            if (articulo.fechaAdquisicion == null) {
                articulo.fechaAdquisicion = LocalDate.now();
            }

            Map<String, Object> valores = construirMapaColumnasInventario(conn, origen, metaInv, articulo, usuarioAdmin, true);
            if (metaInv.pkColumna != null && !metaInv.pkAutoincremental && !valores.containsKey(metaInv.pkColumna)) {
                Integer tipoSqlPk = metaInv.tipoSqlColumnas.get(metaInv.pkColumna);
                if (tipoSqlPk != null && (tipoSqlPk == Types.INTEGER || tipoSqlPk == Types.BIGINT || tipoSqlPk == Types.SMALLINT)) {
                    long sigId = obtenerSiguienteIdEntero(conn, metaInv.nombreReal, metaInv.pkColumna);
                    valores.put(metaInv.pkColumna, sigId);
                    articulo.id = sigId;
                }
            }

            List<String> cols = new ArrayList<>(valores.keySet());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(metaInv.nombreReal).append(" (");
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
                sql.append(columnaSqlSegura(metaInv, cols.get(i)));
                placeholders.append("?");
            }
            sql.append(") VALUES (").append(placeholders).append(")");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < cols.size(); i++) {
                    asignarParametroSql(ps, i + 1, valores.get(cols.get(i)));
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys != null && keys.next()) {
                        long genId = keys.getLong(1);
                        if (genId > 0) {
                            articulo.id = genId;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (articulo.id == null) {
                articulo.id = Math.abs((long) articulo.numeroInventario.hashCode());
            }
            return articulo;
        }
    }

    public Articulo actualizarArticulo(Long id, Articulo datos, String usuarioAdmin) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(datos.fondoOrigen);
        if (origen == null) {
            throw new SQLException("No hay origen externo activo para actualizar el artículo");
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) {
                throw new SQLException("Tabla inventario no encontrada");
            }

            Map<String, Object> valores = construirMapaColumnasInventario(conn, origen, metaInv, datos, usuarioAdmin, false);
            valores.remove(metaInv.pkColumna);
            if (valores.isEmpty()) {
                datos.id = id;
                return datos;
            }

            List<String> cols = new ArrayList<>(valores.keySet());
            StringBuilder sql = new StringBuilder("UPDATE ").append(metaInv.nombreReal).append(" SET ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append(columnaSqlSegura(metaInv, cols.get(i))).append(" = ?");
            }

            boolean usarInvofm = (datos.numeroInventario != null && !datos.numeroInventario.isBlank() && metaInv.columnas.contains("invofm"))
                    && (metaInv.pkColumna == null || "invofm".equals(metaInv.pkColumna));
            if (usarInvofm) {
                sql.append(" WHERE invofm = ?");
            } else {
                sql.append(" WHERE ").append(metaInv.pkColumna).append(" = ?");
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                for (String col : cols) {
                    asignarParametroSql(ps, idx++, valores.get(col));
                }
                if (usarInvofm) {
                    ps.setString(idx, truncarSegunMeta(metaInv, "invofm", datos.numeroInventario));
                } else {
                    ps.setObject(idx, id);
                }
                int updated = ps.executeUpdate();
                if (updated == 0 && datos.numeroInventario != null && metaInv.columnas.contains("invofm") && !usarInvofm) {
                    String fallbackSql = sql.substring(0, sql.lastIndexOf(" WHERE ")) + " WHERE invofm = ?";
                    try (PreparedStatement ps2 = conn.prepareStatement(fallbackSql)) {
                        int idx2 = 1;
                        for (String col : cols) {
                            asignarParametroSql(ps2, idx2++, valores.get(col));
                        }
                        ps2.setString(idx2, truncarSegunMeta(metaInv, "invofm", datos.numeroInventario));
                        ps2.executeUpdate();
                    }
                }
            }
            datos.id = id;
            return datos;
        }
    }

    public void actualizarImagenArticulo(Long id, String numeroInventario, String fondoOrigen, String imagenBase64) {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(fondoOrigen);
        if (origen == null) return;
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta metaInv = inspeccionarTabla(conn, origen, "inventario");
            if (metaInv == null) return;
            String colImg = metaInv.columnas.contains("imagen_bien_base64") ? "imagen_bien_base64"
                    : (metaInv.columnas.contains("imagen") ? "imagen" : (metaInv.columnas.contains("foto") ? "foto" : null));
            if (colImg == null) return;
            String whereCol = (numeroInventario != null && !numeroInventario.isBlank() && metaInv.columnas.contains("invofm")) ? "invofm" : metaInv.pkColumna;
            if (whereCol == null) return;
            String sql = "UPDATE " + metaInv.nombreReal + " SET " + colImg + " = ? WHERE " + whereCol + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, imagenBase64);
                ps.setObject(2, "invofm".equals(whereCol) ? numeroInventario : id);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    public void reclasificarFondoArticulo(Long id, Articulo articulo, String fondoAnterior, String nuevoFondo, String usuarioAdmin) {
        try {
            MultiDataSourceConfig.OrigenDatos origenDestino = resolverOrigenInventarioActivo(nuevoFondo);
            if (origenDestino != null) {
                insertarArticulo(articulo, usuarioAdmin);
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> construirMapaColumnasInventario(
            Connection conn,
            MultiDataSourceConfig.OrigenDatos origen,
            TablaMeta meta,
            Articulo a,
            String usuarioAdmin,
            boolean esAlta
    ) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();

        ponerTexto(meta, m, "cversp_act", extraerClaveInicial(a.resguardanteActual));
        ponerTexto(meta, m, "tipo_fin", a.cuentaContable);
        ponerTexto(meta, m, "folio", a.folioResguardo);
        ponerTexto(meta, m, "tipofolio", a.tipoFolioResguardo);
        if (esAlta) {
            ponerTexto(meta, m, "elaboracion", usuarioAdmin);
            ponerFecha(meta, m, "fecha_elaboracion", LocalDate.now());
            ponerTexto(meta, m, "hora_de_elaboracion", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        ponerTexto(meta, m, "docto", a.documentoResguardo);
        ponerFecha(meta, m, "fecres", a.fechaResguardo);
        ponerTexto(meta, m, "invofm", a.numeroInventario);
        ponerTexto(meta, m, "inv_ant", a.numeroInventarioAnterior);
        ponerTexto(meta, m, "descripcion", a.descripcion);
        ponerTexto(meta, m, "descripcion1", a.descripcionSecundaria);
        ponerTexto(meta, m, "marca", a.marca);
        ponerTexto(meta, m, "modelo", a.modelo);
        ponerTexto(meta, m, "serie", a.numeroSerie);
        ponerTexto(meta, m, "color", a.color);
        ponerTexto(meta, m, "estado", mapearEstadoFisicoACodigo(a.estadoFisico));
        ponerTexto(meta, m, "proveedor", a.proveedor);
        ponerTexto(meta, m, "pedido", a.pedido);
        ponerTexto(meta, m, "requisito", a.requisicion);
        ponerTexto(meta, m, "requis", a.requisicion);
        ponerTexto(meta, m, "salida", a.boletaSalida);
        if (meta.columnas.contains("precio")) {
            Double precio = a.valorInicial != null ? a.valorInicial : a.precioSinIva;
            m.put("precio", precio != null ? precio : 0.0);
        }
        ponerTexto(meta, m, "dactura", a.factura);
        ponerTexto(meta, m, "factura", a.factura);
        ponerTexto(meta, m, "folio_fiscal", a.folioFiscalUuid);
        ponerTexto(meta, m, "fol_fiscal", a.folioFiscalUuid);
        ponerFecha(meta, m, "fecha_factura", a.fechaFactura);
        ponerFecha(meta, m, "fechafact", a.fechaFactura);
        ponerTexto(meta, m, "ofibaja", a.oficioBaja);
        ponerTexto(meta, m, "numero_baja", a.oficioBaja);
        ponerFecha(meta, m, "fechabaja", a.fechaBaja);
        ponerFecha(meta, m, "fecbaja", a.fechaBaja);
        ponerTexto(meta, m, "numero_acta", a.numeroActa);
        ponerTexto(meta, m, "numacta", a.numeroActa);
        ponerFecha(meta, m, "fecha_acta", a.fechaActa);
        ponerFecha(meta, m, "fecacta", a.fechaActa);
        ponerTexto(meta, m, "tipo_baja", a.tipoBaja);
        ponerTexto(meta, m, "carp_inves", a.carpetaInvestigacion);
        ponerTexto(meta, m, "natgasto", extraerClaveInicial(a.naturalezaGasto));
        ponerTexto(meta, m, "partida", extraerClaveInicial(a.partida));
        ponerTexto(meta, m, "origen", a.fondoOrigen);
        ponerTexto(meta, m, "placa", a.placa);
        ponerTexto(meta, m, "motor", a.motor);
        ponerTexto(meta, m, "poliza", a.poliza);
        ponerFecha(meta, m, "vigendesde", a.vigenciaDesde);
        ponerFecha(meta, m, "vigenhasta", a.vigenciaPoliza);
        ponerTexto(meta, m, "usuario", usuarioAdmin);

        // Resolución automática de FKs hacia las tablas de catálogo reales (id_* y fk_*)
        if (a.centroTrabajo != null && !a.centroTrabajo.isBlank()) {
            Integer fkCct = resolverFkEnTabla(conn, origen, "cct", "id_cct", new String[]{"clave_ct", "nombre_ct"}, a.centroTrabajo);
            if (fkCct != null) {
                if (meta.columnas.contains("id_cct")) m.put("id_cct", fkCct);
                if (meta.columnas.contains("fk_cct")) m.put("fk_cct", fkCct);
            }
        }
        if (a.proveedor != null && !a.proveedor.isBlank()) {
            Integer fkProv = resolverFkEnTabla(conn, origen, "proveedores", "id_proveedor", new String[]{"nombre", "rfc"}, a.proveedor);
            if (fkProv != null) {
                if (meta.columnas.contains("id_proveedor")) m.put("id_proveedor", fkProv);
                if (meta.columnas.contains("fk_proveedor")) m.put("fk_proveedor", fkProv);
            }
        }
        if (a.resguardanteActual != null && !a.resguardanteActual.isBlank()) {
            TablaMeta metaResp = inspeccionarTabla(conn, origen, "responsables");
            if (metaResp != null && metaResp.pkColumna != null) {
                String respKey = a.resguardanteActual;
                int parenIdx = respKey.lastIndexOf('(');
                int parenClose = respKey.lastIndexOf(')');
                if (parenIdx > 0 && parenClose > parenIdx) {
                    respKey = respKey.substring(parenIdx + 1, parenClose).trim();
                }
                Integer fkResp = resolverFkEnTabla(conn, origen, "responsables", metaResp.pkColumna, new String[]{"rfc", "rfc2", "curp", "nombres"}, respKey);
                if (fkResp == null) {
                    fkResp = resolverFkEnTabla(conn, origen, "responsables", metaResp.pkColumna, new String[]{"rfc", "rfc2", "curp", "nombres"}, a.resguardanteActual);
                }
                if (fkResp != null) {
                    if (meta.columnas.contains("id_responsable")) m.put("id_responsable", fkResp);
                    if (meta.columnas.contains("fk_responsable")) m.put("fk_responsable", fkResp);
                }
            }
        }
        if (a.marca != null && !a.marca.isBlank()) {
            Integer fkMarca = resolverFkEnTabla(conn, origen, "marcas", "id_marca", new String[]{"descripcion", "clave"}, a.marca);
            if (fkMarca != null) {
                if (meta.columnas.contains("id_marca")) m.put("id_marca", fkMarca);
                if (meta.columnas.contains("fk_marca")) m.put("fk_marca", fkMarca);
            }
        }
        if (a.naturalezaGasto != null && !a.naturalezaGasto.isBlank()) {
            Integer fkNat = resolverFkEnTabla(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", new String[]{"clave", "descripcion"}, extraerClaveInicial(a.naturalezaGasto));
            if (fkNat != null) {
                if (meta.columnas.contains("id_naturaleza_gastos")) m.put("id_naturaleza_gastos", fkNat);
                if (meta.columnas.contains("fk_naturalezagastos")) m.put("fk_naturalezagastos", fkNat);
            }
        }
        if (a.tipoFolioResguardo != null && !a.tipoFolioResguardo.isBlank()) {
            Integer fkTf = resolverFkEnTabla(conn, origen, "tipos_folios", "tipo_folio_id", new String[]{"clave", "descripcion"}, a.tipoFolioResguardo);
            if (fkTf != null) {
                if (meta.columnas.contains("id_tipo_folio")) m.put("id_tipo_folio", fkTf);
                if (meta.columnas.contains("fk_tipofolio")) m.put("fk_tipofolio", fkTf);
            }
        }
        if (a.tipoMaterial != null && !a.tipoMaterial.isBlank()) {
            Integer fkTipo = resolverFkEnTabla(conn, origen, "tipos", "id_tipo", new String[]{"tipos", "descripcion", "subclave"}, extraerClaveInicial(a.tipoMaterial));
            if (fkTipo != null) {
                if (meta.columnas.contains("id_tipo")) m.put("id_tipo", fkTipo);
                if (meta.columnas.contains("fk_tipo")) m.put("fk_tipo", fkTipo);
            }
        }
        if (a.partida != null && !a.partida.isBlank()) {
            Integer fkPart = resolverFkEnTabla(conn, origen, "partidas", "id_partida", new String[]{"pa_partida", "pa_descripcion"}, extraerClaveInicial(a.partida));
            if (fkPart != null) {
                if (meta.columnas.contains("id_partida")) m.put("id_partida", fkPart);
                if (meta.columnas.contains("fk_partida")) m.put("fk_partida", fkPart);
                if (meta.columnas.contains("id_partida_anterior")) m.putIfAbsent("id_partida_anterior", fkPart);
                if (meta.columnas.contains("fk_partida_anterior")) m.putIfAbsent("fk_partida_anterior", fkPart);
            }
        }
        return m;
    }

    private Articulo mapearResultSetAArticulo(
            ResultSet rs,
            TablaMeta metaInv,
            MultiDataSourceConfig.OrigenDatos origen,
            long fallbackRowId,
            Map<Integer, String> mapaCct,
            Map<Integer, String> mapaPartidas,
            Map<Integer, String> mapaMarcas,
            Map<Integer, String> mapaProveedores,
            Map<Integer, String> mapaTipos,
            Map<Integer, String> mapaNatGasto,
            Map<Integer, String> mapaTiposFolios
    ) throws SQLException {
        Articulo a = new Articulo();
        if (metaInv.pkColumna != null && metaInv.columnas.contains(metaInv.pkColumna)) {
            try {
                long pkVal = rs.getLong(metaInv.pkColumna);
                a.id = pkVal != 0 ? pkVal : fallbackRowId;
            } catch (Exception e) {
                a.id = fallbackRowId;
            }
        } else {
            a.id = fallbackRowId;
        }

        a.numeroInventario = leerString(rs, metaInv, "invofm");
        a.numeroInventarioAnterior = leerString(rs, metaInv, "inv_ant");
        String origenCol = leerString(rs, metaInv, "origen");
        if (origenCol != null && !origenCol.isBlank()) {
            a.fondoOrigen = origenCol;
        } else if (origen == MultiDataSourceConfig.OrigenDatos.INVENTARIO_INTERNO) {
            a.fondoOrigen = "INTERNO";
        } else if (origen == MultiDataSourceConfig.OrigenDatos.INVENTARIO_OFICIAL_DESAGREGADOS) {
            a.fondoOrigen = "OFICIAL_DESEGREGADOS";
        } else {
            a.fondoOrigen = "OFICIAL";
        }
        a.tokenQR = a.numeroInventario != null ? a.numeroInventario : String.valueOf(a.id);

        a.descripcion = leerString(rs, metaInv, "descripcion");
        a.descripcionSecundaria = leerString(rs, metaInv, "descripcion1");
        a.marca = leerString(rs, metaInv, "marca");
        if (a.marca == null || a.marca.isBlank()) {
            Integer fkMarca = leerPrimerEnteroNoNulo(rs, metaInv, "id_marca", "fk_marca");
            if (fkMarca != null) a.marca = mapaMarcas.get(fkMarca);
        }
        a.modelo = leerString(rs, metaInv, "modelo");
        a.numeroSerie = leerString(rs, metaInv, "serie");
        a.color = leerString(rs, metaInv, "color");
        a.estadoFisico = mapearCodigoAEstadoFisico(leerString(rs, metaInv, "estado"));
        a.proveedor = leerString(rs, metaInv, "proveedor");
        if (a.proveedor == null || a.proveedor.isBlank()) {
            Integer fkProv = leerPrimerEnteroNoNulo(rs, metaInv, "id_proveedor", "fk_proveedor");
            if (fkProv != null) a.proveedor = mapaProveedores.get(fkProv);
        }
        a.pedido = leerString(rs, metaInv, "pedido");
        a.requisicion = leerPrimerTextoNoVacio(rs, metaInv, "requisito", "requis");
        a.boletaSalida = leerString(rs, metaInv, "salida");
        Double precio = leerDouble(rs, metaInv, "precio");
        if (precio != null) {
            a.valorInicial = precio;
            a.precioSinIva = Math.round((precio / 1.16) * 100.0) / 100.0;
        }
        a.factura = leerPrimerTextoNoVacio(rs, metaInv, "dactura", "factura");
        a.folioFiscalUuid = leerPrimerTextoNoVacio(rs, metaInv, "folio_fiscal", "fol_fiscal");
        a.fechaFactura = leerPrimeraFechaNoNula(rs, metaInv, "fecha_factura", "fechafact");
        a.oficioBaja = leerPrimerTextoNoVacio(rs, metaInv, "ofibaja", "numero_baja");
        a.fechaBaja = leerPrimeraFechaNoNula(rs, metaInv, "fechabaja", "fecbaja");
        a.numeroActa = leerPrimerTextoNoVacio(rs, metaInv, "numero_acta", "numacta");
        a.fechaActa = leerPrimeraFechaNoNula(rs, metaInv, "fecha_acta", "fecacta");
        a.tipoBaja = leerString(rs, metaInv, "tipo_baja");
        a.carpetaInvestigacion = leerString(rs, metaInv, "carp_inves");
        a.estado = (a.fechaBaja != null || (a.oficioBaja != null && !a.oficioBaja.isBlank())) ? "Baja" : "Activo";

        a.naturalezaGasto = leerString(rs, metaInv, "natgasto");
        if (a.naturalezaGasto == null || a.naturalezaGasto.isBlank()) {
            Integer fkNat = leerPrimerEnteroNoNulo(rs, metaInv, "id_naturaleza_gastos", "fk_naturalezagastos");
            if (fkNat != null) a.naturalezaGasto = mapaNatGasto.get(fkNat);
        }
        a.partida = leerString(rs, metaInv, "partida");
        if (a.partida == null || a.partida.isBlank()) {
            Integer fkPart = leerPrimerEnteroNoNulo(rs, metaInv, "id_partida", "fk_partida", "id_partida_anterior", "fk_partida_anterior");
            if (fkPart != null) a.partida = mapaPartidas.get(fkPart);
        }
        Integer fkTipo = leerPrimerEnteroNoNulo(rs, metaInv, "id_tipo", "fk_tipo");
        if (fkTipo != null) {
            a.tipoMaterial = mapaTipos.get(fkTipo);
        }

        Integer fkCct = leerPrimerEnteroNoNulo(rs, metaInv, "id_cct", "fk_cct");
        CentroTrabajo ctObj = obtenerCentroTrabajoPorId(fkCct);
        if (ctObj != null) {
            if (ctObj.claveCT != null && !ctObj.claveCT.isBlank()) {
                a.centroTrabajo = ctObj.claveCT.trim().toUpperCase(Locale.ROOT);
            }
            if (ctObj.nombreCT != null && !ctObj.nombreCT.isBlank()) {
                a.areaUbicacion = ctObj.nombreCT.trim();
            }
            a.unidadResponsable = ctObj.unidadResp;
            a.subdireccion = ctObj.subDireccion;
            a.departamento = ctObj.departamento;
        } else if (fkCct != null) {
            a.centroTrabajo = mapaCct.get(fkCct);
        }

        if (a.centroTrabajo == null || a.centroTrabajo.isBlank()) {
            String[] colsTextoCct = {"clave_ct", "cct", "centro_trabajo", "adscripcion", "ubicacion", "depto"};
            for (String colCct : colsTextoCct) {
                String valCct = leerString(rs, metaInv, colCct);
                if (valCct != null && !valCct.isBlank()) {
                    a.centroTrabajo = valCct.trim().toUpperCase(Locale.ROOT);
                    break;
                }
            }
        }

        String rawResp = leerPrimerTextoNoVacio(rs, metaInv, "cversp_act", "responsable", "resguardante");
        Integer fkRespId = leerPrimerEnteroNoNulo(rs, metaInv, "id_responsable", "fk_responsable");

        Empleado directorResuelto = resolverDirectorEnCache(fkRespId, rawResp, a.centroTrabajo);
        if (directorResuelto != null) {
            String rfcTag = (directorResuelto.rfc != null && !directorResuelto.rfc.isBlank() && !directorResuelto.nombre.contains(directorResuelto.rfc))
                    ? (" (" + directorResuelto.rfc + ")")
                    : "";
            a.resguardanteActual = directorResuelto.nombre + rfcTag;
            if ((a.centroTrabajo == null || a.centroTrabajo.isBlank()) && directorResuelto.cct != null && !directorResuelto.cct.isBlank()) {
                a.centroTrabajo = directorResuelto.cct;
                if (a.areaUbicacion == null || a.areaUbicacion.isBlank()) {
                    a.areaUbicacion = directorResuelto.nombreEscuela;
                }
            }
        } else if (ctObj != null && ctObj.director != null && !ctObj.director.isBlank()) {
            a.resguardanteActual = ctObj.director.trim().toUpperCase(Locale.ROOT);
        } else {
            a.resguardanteActual = rawResp;
        }

        a.cuentaContable = leerString(rs, metaInv, "tipo_fin");
        a.folioResguardo = leerString(rs, metaInv, "folio");
        a.tipoFolioResguardo = leerString(rs, metaInv, "tipofolio");
        if (a.tipoFolioResguardo == null || a.tipoFolioResguardo.isBlank()) {
            Integer fkTf = leerPrimerEnteroNoNulo(rs, metaInv, "id_tipo_folio", "fk_tipofolio");
            if (fkTf != null) a.tipoFolioResguardo = mapaTiposFolios.get(fkTf);
        }
        a.documentoResguardo = leerString(rs, metaInv, "docto");
        a.fechaResguardo = leerFecha(rs, metaInv, "fecres");
        a.fechaAdquisicion = leerFecha(rs, metaInv, "fecha_elaboracion");
        a.placa = leerString(rs, metaInv, "placa");
        a.motor = leerString(rs, metaInv, "motor");
        a.poliza = leerString(rs, metaInv, "poliza");
        a.vigenciaDesde = leerFecha(rs, metaInv, "vigendesde");
        a.vigenciaPoliza = leerFecha(rs, metaInv, "vigenhasta");
        a.categoria = (a.placa != null && !a.placa.isBlank()) ? "Vehiculos" : "Mobiliario";

        boolean esBaja = a.fechaBaja != null
                || (a.oficioBaja != null && !a.oficioBaja.isBlank())
                || "BAJ".equalsIgnoreCase(a.tipoFolioResguardo);
        boolean tieneCctAsignado = (fkCct != null && fkCct > 0)
                || (a.centroTrabajo != null && !a.centroTrabajo.isBlank()
                && !"CENTRAL".equalsIgnoreCase(a.centroTrabajo.trim())
                && !"ALMACENGENERAL".equalsIgnoreCase(a.centroTrabajo.trim())
                && !"SIN-CCT".equalsIgnoreCase(a.centroTrabajo.trim()));
        boolean tieneResponsableAsignado = (fkRespId != null && fkRespId > 0)
                || (rawResp != null && !rawResp.isBlank())
                || (a.resguardanteActual != null && !a.resguardanteActual.isBlank());

        if (esBaja) {
            a.cantidadStock = 0.0;
            a.estado = "Baja";
        } else if (tieneCctAsignado || tieneResponsableAsignado) {
            a.cantidadStock = 0.0;
            a.estado = "En Resguardo CCT";
        } else {
            a.cantidadStock = 1.0;
            a.estado = "Disponible en Almacén";
        }

        if (a.areaUbicacion == null || a.areaUbicacion.isBlank()) {
            if (a.centroTrabajo != null && !a.centroTrabajo.isBlank()) {
                String claveCct = a.centroTrabajo.trim().toUpperCase(Locale.ROOT);
                a.centroTrabajo = claveCct;
                a.areaUbicacion = "CCT: " + claveCct;
            } else if (a.resguardanteActual != null && !a.resguardanteActual.isBlank()) {
                a.areaUbicacion = "Director Escolar: " + a.resguardanteActual;
            } else {
                a.areaUbicacion = "AlmacenGeneral";
            }
        }
        return a;
    }

    private CentroTrabajo obtenerCentroTrabajoPorId(Integer idCct) {
        if (idCct == null || idCct <= 0) {
            return null;
        }
        try {
            CacheEntry<Map<Integer, CentroTrabajo>> cached = cacheCctPorId;
            if (cached != null && cached.vigente() && cached.valor != null) {
                CentroTrabajo found = cached.valor.get(idCct);
                if (found != null) {
                    return found;
                }
            }
            MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
            if (origen == null) {
                return null;
            }
            try (Connection conn = multiDataSourceConfig.obtenerConexion(origen);
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id_cct, clave_ct, nombre_ct, unid_responsable, subdirector, departamento, director FROM inventario.cct WHERE id_cct = ? LIMIT 1"
                 )) {
                ps.setInt(1, idCct);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        CentroTrabajo ct = new CentroTrabajo();
                        ct.id = rs.getLong("id_cct");
                        ct.claveCT = rs.getString("clave_ct");
                        ct.nombreCT = rs.getString("nombre_ct");
                        ct.unidadResp = rs.getString("unid_responsable");
                        ct.subDireccion = rs.getString("subdirector");
                        ct.departamento = rs.getString("departamento");
                        ct.director = rs.getString("director");
                        if (cached == null || !cached.vigente() || cached.valor == null) {
                            Map<Integer, CentroTrabajo> nuevoMapa = new ConcurrentHashMap<>();
                            nuevoMapa.put(idCct, ct);
                            cacheCctPorId = new CacheEntry<>(nuevoMapa, TTL_CACHE_MS);
                        } else {
                            cached.valor.put(idCct, ct);
                        }
                        return ct;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Empleado resolverDirectorEnCache(Integer fkRespId, String rawResp, String claveCct) {
        try {
            CacheEntry<Map<String, Empleado>> idxCache = cacheIndiceDirectores;
            if (idxCache == null || !idxCache.vigente()) {
                listarResponsables();
                idxCache = cacheIndiceDirectores;
            }
            if (idxCache == null || idxCache.valor == null || idxCache.valor.isEmpty()) {
                return null;
            }
            Map<String, Empleado> indice = idxCache.valor;
            if (fkRespId != null && fkRespId > 0) {
                Empleado porId = indice.get("ID::" + fkRespId.longValue());
                if (porId != null) return porId;
            }
            if (rawResp != null && !rawResp.isBlank()) {
                String limpio = rawResp.trim().toUpperCase(Locale.ROOT);
                Empleado porIdTexto = indice.get("ID::" + limpio);
                if (porIdTexto != null) return porIdTexto;
                Empleado porClave = indice.get("KEY::" + limpio);
                if (porClave != null) return porClave;
            }
            if (claveCct != null && !claveCct.isBlank()) {
                String cctUp = claveCct.trim().toUpperCase(Locale.ROOT);
                Empleado porCct = indice.get("CCT::" + cctUp);
                if (porCct != null) return porCct;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =========================================================================
    // 2. CRUD DE CENTROS DE TRABAJO (cct <-> CentroTrabajo)
    // =========================================================================

    public List<CentroTrabajo> listarCentrosTrabajo() throws SQLException {
        CacheEntry<List<CentroTrabajo>> cached = cacheCentrosTrabajo;
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        if (origen == null) {
            return Collections.emptyList();
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "cct");
            if (meta == null) {
                return Collections.emptyList();
            }

            Map<String, String> mapaMunicipios = new HashMap<>();
            try {
                TablaMeta metaMun = inspeccionarTabla(conn, origen, "municipio");
                if (metaMun != null && metaMun.columnas.contains("clave") && metaMun.columnas.contains("descripcion")) {
                    try (PreparedStatement psMun = conn.prepareStatement("SELECT clave, descripcion FROM " + metaMun.nombreReal);
                         ResultSet rsMun = psMun.executeQuery()) {
                        while (rsMun.next()) {
                            String cve = rsMun.getString(1);
                            String desc = rsMun.getString(2);
                            if (cve != null && desc != null) {
                                String k = cve.trim();
                                mapaMunicipios.put(k, k + " - " + desc.trim());
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            String[] colsRequeridas = {
                "id_cct", "clave_ct", "clave_ct1", "nombre_ct", "nomct_ant",
                "unid_responsable", "subdirector", "departamento", "domicilio",
                "codigopostal", "codpost", "municipio", "localidad", "telefono",
                "numero_exterior", "numero_interior", "clave_vial", "clave_asen",
                "nombre_asen", "vialid_der", "vialid_izq", "viali_post",
                "descripcion_ubc", "turno", "zonaescolar", "zonaescola",
                "sector", "director", "status", "activo"
            };
            List<String> colsPresentes = new ArrayList<>();
            for (String col : colsRequeridas) {
                if (meta.columnas.contains(col)) {
                    colsPresentes.add(columnaSqlSegura(meta, col));
                }
            }
            String selectList = colsPresentes.isEmpty() ? "*" : String.join(", ", colsPresentes);

            List<CentroTrabajo> lista = new ArrayList<>();
            Map<Integer, CentroTrabajo> porId = new ConcurrentHashMap<>();
            String sql = "SELECT " + selectList + " FROM " + meta.nombreReal
                    + " ORDER BY " + (meta.pkColumna != null ? meta.pkColumna : "1") + " ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                long idx = 1L;
                while (rs.next()) {
                    CentroTrabajo ct = new CentroTrabajo();
                    Long idVal = leerLong(rs, meta, "id_cct");
                    ct.id = idVal != null ? idVal : idx++;
                    ct.claveCT = leerPrimerTextoNoVacio(rs, meta, "clave_ct", "clave_ct1");
                    ct.nombreCT = leerPrimerTextoNoVacio(rs, meta, "nombre_ct", "nomct_ant");
                    ct.unidadResp = leerString(rs, meta, "unid_responsable");
                    ct.subDireccion = leerString(rs, meta, "subdirector");
                    ct.departamento = leerString(rs, meta, "departamento");
                    ct.nombreVial = leerString(rs, meta, "domicilio");
                    ct.codPostal = leerPrimerTextoNoVacio(rs, meta, "codigopostal", "codpost");
                    String rawMun = leerString(rs, meta, "municipio");
                    if (rawMun != null && !rawMun.isBlank()) {
                        ct.municipio = mapaMunicipios.getOrDefault(rawMun.trim(), rawMun.trim());
                    }
                    ct.localidad = leerString(rs, meta, "localidad");
                    ct.telefono = leerString(rs, meta, "telefono");
                    ct.numExt = leerString(rs, meta, "numero_exterior");
                    ct.numInt = leerString(rs, meta, "numero_interior");
                    ct.tipoVial = leerString(rs, meta, "clave_vial");
                    ct.tipoAsent = leerString(rs, meta, "clave_asen");
                    ct.nomAsent = leerString(rs, meta, "nombre_asen");
                    ct.vialidDer = leerString(rs, meta, "vialid_der");
                    ct.vialidIzq = leerString(rs, meta, "vialid_izq");
                    ct.vialidPost = leerString(rs, meta, "viali_post");
                    ct.refVialid = leerString(rs, meta, "descripcion_ubc");
                    ct.turno = leerString(rs, meta, "turno");
                    ct.zonaEscolar = leerPrimerTextoNoVacio(rs, meta, "zonaescolar", "zonaescola");
                    ct.sector = leerString(rs, meta, "sector");
                    ct.director = leerString(rs, meta, "director");
                    String st = leerString(rs, meta, "status");
                    Integer act = leerEntero(rs, meta, "activo");
                    ct.estatus = (st != null && !st.isBlank()) ? st : ((act != null && act == 0) ? "B" : "A");
                    lista.add(ct);
                    if (ct.id != null) {
                        porId.put(ct.id.intValue(), ct);
                    }
                }
            }
            cacheCctPorId = new CacheEntry<>(porId, TTL_CACHE_MS);
            cacheCentrosTrabajo = new CacheEntry<>(lista, TTL_CACHE_MS);
            return lista;
        }
    }

    public CentroTrabajo crearCentroTrabajo(CentroTrabajo ct) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "cct");
            Map<String, Object> m = mapearCamposCCT(meta, ct);
            if (meta.pkColumna != null && !meta.pkAutoincremental) {
                long sigId = obtenerSiguienteIdEntero(conn, meta.nombreReal, meta.pkColumna);
                m.put(meta.pkColumna, sigId);
                ct.id = sigId;
            }
            ejecutarInsertGenerico(conn, meta, m, idGen -> ct.id = idGen);
            cacheCentrosTrabajo = null;
            cacheCctPorId = null;
            cacheResponsables = null;
            cacheMapasSimples.keySet().removeIf(k -> k.contains("::cct::"));
            return ct;
        }
    }

    public CentroTrabajo actualizarCentroTrabajo(Long id, CentroTrabajo ct) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "cct");
            Map<String, Object> m = mapearCamposCCT(meta, ct);
            m.remove(meta.pkColumna);
            ejecutarUpdateGenerico(conn, meta, m, id);
            ct.id = id;
            cacheCentrosTrabajo = null;
            cacheCctPorId = null;
            cacheResponsables = null;
            cacheMapasSimples.keySet().removeIf(k -> k.contains("::cct::"));
            return ct;
        }
    }

    private Map<String, Object> mapearCamposCCT(TablaMeta meta, CentroTrabajo ct) {
        Map<String, Object> m = new LinkedHashMap<>();
        ponerTexto(meta, m, "unid_responsable", ct.unidadResp);
        ponerTexto(meta, m, "subdirector", ct.subDireccion);
        ponerTexto(meta, m, "departamento", ct.departamento);
        ponerTexto(meta, m, "clave_ct", ct.claveCT);
        ponerTexto(meta, m, "clave_ct1", ct.claveCT);
        if (ct.claveCT != null && ct.claveCT.length() >= 5) {
            ponerTexto(meta, m, "letras_ct", ct.claveCT.substring(2, 5));
        }
        ponerTexto(meta, m, "nombre_ct", ct.nombreCT);
        ponerTexto(meta, m, "domicilio", ct.nombreVial);
        ponerTexto(meta, m, "codigopostal", ct.codPostal);
        ponerTexto(meta, m, "codpost", ct.codPostal);
        ponerTexto(meta, m, "municipio", extraerClaveInicial(ct.municipio));
        ponerTexto(meta, m, "localidad", extraerClaveInicial(ct.localidad));
        ponerTexto(meta, m, "telefono", ct.telefono);
        ponerTexto(meta, m, "numero_exterior", ct.numExt);
        ponerTexto(meta, m, "numero_interior", ct.numInt);
        ponerTexto(meta, m, "clave_vial", extraerClaveInicial(ct.tipoVial));
        ponerTexto(meta, m, "clave_asen", extraerClaveInicial(ct.tipoAsent));
        ponerTexto(meta, m, "nombre_asen", ct.nomAsent);
        ponerTexto(meta, m, "vialid_der", ct.vialidDer);
        ponerTexto(meta, m, "vialid_izq", ct.vialidIzq);
        ponerTexto(meta, m, "viali_post", ct.vialidPost);
        ponerTexto(meta, m, "descripcion_ubc", ct.refVialid);
        ponerTexto(meta, m, "turno", ct.turno);
        ponerTexto(meta, m, "zonaescolar", ct.zonaEscolar);
        ponerTexto(meta, m, "zonaescola", ct.zonaEscolar);
        ponerTexto(meta, m, "sector", ct.sector);
        ponerTexto(meta, m, "director", ct.director);
        ponerTexto(meta, m, "status", ct.estatus != null ? ct.estatus : "A");
        if (meta.columnas.contains("activo")) {
            m.put("activo", "B".equalsIgnoreCase(ct.estatus) ? 0 : 1);
        }
        return m;
    }

    // =========================================================================
    // 3. CRUD DE RESPONSABLES (responsables <-> Empleado)
    // =========================================================================

    public List<Empleado> listarResponsables() throws SQLException {
        return listarResponsables(null, 600);
    }

    public List<Empleado> listarResponsables(String busqueda, int limite) throws SQLException {
        boolean sinFiltro = (busqueda == null || busqueda.isBlank());
        int lim = limite > 0 ? Math.min(limite, 2000) : 600;
        if (sinFiltro && lim == 600) {
            CacheEntry<List<Empleado>> cached = cacheResponsables;
            if (cached != null && cached.vigente()) {
                return cached.valor;
            }
        }

        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        if (origen == null) {
            return Collections.emptyList();
        }

        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            StringBuilder sql = new StringBuilder(
                    "SELECT r.id_responsable, r.rfc, r.rfc2, r.curp, r.nombres, r.paterno, r.materno, r.apellidos, r.activo, "
                    + "c.clave_ct, c.nombre_ct, "
                    + "CASE WHEN inv.id_responsable IS NOT NULL OR c.id_cct IS NOT NULL THEN 1 ELSE 0 END AS prioridad "
                    + "FROM inventario.responsables r "
                    + "LEFT JOIN ( "
                    + "    SELECT DISTINCT ON (id_responsable) id_responsable, id_cct "
                    + "    FROM inventario.inventario "
                    + "    WHERE id_responsable IS NOT NULL AND id_cct IS NOT NULL "
                    + ") inv ON inv.id_responsable = r.id_responsable "
                    + "LEFT JOIN inventario.cct c ON c.id_cct = inv.id_cct "
            );

            String term = null;
            if (!sinFiltro) {
                term = "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
                sql.append("WHERE LOWER(COALESCE(r.rfc,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(r.nombres,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(r.paterno,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(r.materno,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(r.apellidos,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(c.clave_ct,'')) LIKE ? ")
                   .append("OR LOWER(COALESCE(c.nombre_ct,'')) LIKE ? ");
            }
            sql.append("ORDER BY prioridad DESC, r.id_responsable ASC LIMIT ?");

            List<Empleado> lista = new ArrayList<>();
            Set<String> cctsAgregados = new LinkedHashSet<>();

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int paramIdx = 1;
                if (term != null) {
                    for (int i = 0; i < 7; i++) {
                        ps.setString(paramIdx++, term);
                    }
                }
                ps.setInt(paramIdx, lim);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Empleado emp = construirEmpleadoDesdeJoinResultSet(rs);
                        if (emp.cct != null && !emp.cct.isBlank()) {
                            cctsAgregados.add(emp.cct.trim().toUpperCase(Locale.ROOT));
                        }
                        lista.add(emp);
                    }
                }
            }

            if (lista.size() < lim) {
                int faltantes = lim - lista.size();
                StringBuilder sqlCct = new StringBuilder(
                        "SELECT id_cct, clave_ct, nombre_ct, director, status, activo FROM inventario.cct "
                );
                if (term != null) {
                    sqlCct.append("WHERE LOWER(COALESCE(clave_ct,'')) LIKE ? ")
                          .append("OR LOWER(COALESCE(nombre_ct,'')) LIKE ? ")
                          .append("OR LOWER(COALESCE(director,'')) LIKE ? ");
                }
                sqlCct.append("ORDER BY id_cct ASC LIMIT ?");

                try (PreparedStatement psCct = conn.prepareStatement(sqlCct.toString())) {
                    int idx = 1;
                    if (term != null) {
                        psCct.setString(idx++, term);
                        psCct.setString(idx++, term);
                        psCct.setString(idx++, term);
                    }
                    psCct.setInt(idx, faltantes);
                    try (ResultSet rsCct = psCct.executeQuery()) {
                        while (rsCct.next() && lista.size() < lim) {
                            String claveCt = rsCct.getString("clave_ct");
                            if (claveCt == null || claveCt.isBlank()) continue;
                            String claveUp = claveCt.trim().toUpperCase(Locale.ROOT);
                            if (!cctsAgregados.add(claveUp)) continue;

                            long idCct = rsCct.getLong("id_cct");
                            String nombreCt = rsCct.getString("nombre_ct");
                            String director = rsCct.getString("director");
                            String status = rsCct.getString("status");
                            int activo = rsCct.getInt("activo");

                            Empleado empCct = new Empleado();
                            empCct.idEmpleado = 900_000L + idCct;
                            empCct.cct = claveUp;
                            empCct.nombreEscuela = (nombreCt != null && !nombreCt.isBlank()) ? nombreCt.trim() : ("ESCUELA " + claveUp);
                            empCct.nombre = (director != null && !director.isBlank())
                                    ? director.trim().toUpperCase(Locale.ROOT)
                                    : ("TITULAR CCT " + claveUp + " - " + empCct.nombreEscuela);
                            empCct.rfc = claveUp;
                            empCct.claveResponsable = claveUp;
                            empCct.numeroEmpleado = claveUp;
                            empCct.usuario = claveUp;
                            empCct.area = "Escuela / CCT";
                            empCct.cargo = "DIRECTOR DE CENTRO DE TRABAJO (CCT)";
                            empCct.activo = ("B".equalsIgnoreCase(status) || activo == 0) ? 0 : 1;
                            lista.add(empCct);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (sinFiltro && lim == 600) {
                Map<String, Empleado> indice = new HashMap<>(lista.size() * 4);
                for (Empleado e : lista) {
                    if (e == null) continue;
                    if (e.idEmpleado != null) indice.putIfAbsent("ID::" + e.idEmpleado, e);
                    if (e.rfc != null && !e.rfc.isBlank()) indice.putIfAbsent("KEY::" + e.rfc.trim().toUpperCase(Locale.ROOT), e);
                    if (e.claveResponsable != null && !e.claveResponsable.isBlank()) indice.putIfAbsent("KEY::" + e.claveResponsable.trim().toUpperCase(Locale.ROOT), e);
                    if (e.curp != null && !e.curp.isBlank()) indice.putIfAbsent("KEY::" + e.curp.trim().toUpperCase(Locale.ROOT), e);
                    if (e.nombre != null && !e.nombre.isBlank()) indice.putIfAbsent("KEY::" + e.nombre.trim().toUpperCase(Locale.ROOT), e);
                    if (e.cct != null && !e.cct.isBlank()) indice.putIfAbsent("CCT::" + e.cct.trim().toUpperCase(Locale.ROOT), e);
                }
                cacheIndiceDirectores = new CacheEntry<>(indice, TTL_CACHE_MS);
                cacheResponsables = new CacheEntry<>(lista, TTL_CACHE_MS);
            }
            return lista;
        }
    }

    private Empleado construirEmpleadoDesdeJoinResultSet(ResultSet rs) throws SQLException {
        Empleado emp = new Empleado();
        emp.idEmpleado = rs.getLong("id_responsable");
        String rfc = rs.getString("rfc");
        String rfc2 = rs.getString("rfc2");
        emp.rfc = rfc != null ? rfc.trim() : null;
        emp.claveResponsable = (rfc2 != null && !rfc2.isBlank()) ? rfc2.trim() : emp.rfc;
        emp.numeroEmpleado = emp.claveResponsable;
        emp.usuario = (emp.rfc != null && !emp.rfc.isBlank()) ? emp.rfc : emp.claveResponsable;
        String curp = rs.getString("curp");
        emp.curp = curp != null ? curp.trim() : null;
        String pat = rs.getString("paterno");
        String mat = rs.getString("materno");
        String nombres = rs.getString("nombres");
        String apellidos = rs.getString("apellidos");
        emp.apellidoPaterno = pat != null ? pat.trim() : null;
        emp.apellidoMaterno = mat != null ? mat.trim() : null;

        StringBuilder nomCompleto = new StringBuilder();
        if (nombres != null && !nombres.isBlank()) nomCompleto.append(nombres.trim());
        if (emp.apellidoPaterno != null && !emp.apellidoPaterno.isBlank()) {
            if (nomCompleto.length() > 0) nomCompleto.append(" ");
            nomCompleto.append(emp.apellidoPaterno);
        }
        if (emp.apellidoMaterno != null && !emp.apellidoMaterno.isBlank()) {
            if (nomCompleto.length() > 0) nomCompleto.append(" ");
            nomCompleto.append(emp.apellidoMaterno);
        }
        if (nomCompleto.length() == 0 && apellidos != null && !apellidos.isBlank()) {
            nomCompleto.append(apellidos.trim());
        }
        emp.nombre = nomCompleto.length() > 0
                ? nomCompleto.toString().toUpperCase(Locale.ROOT)
                : (emp.rfc != null ? emp.rfc : "DIRECTOR DE CENTRO DE TRABAJO");

        int act = rs.getInt("activo");
        emp.activo = rs.wasNull() ? 1 : act;

        String claveCt = rs.getString("clave_ct");
        String nombreCt = rs.getString("nombre_ct");
        if (claveCt != null && !claveCt.isBlank()) {
            String claveUp = claveCt.trim().toUpperCase(Locale.ROOT);
            emp.cct = claveUp;
            emp.nombreEscuela = (nombreCt != null && !nombreCt.isBlank())
                    ? nombreCt.trim()
                    : ("CENTRO EDUCATIVO " + claveUp);
            emp.area = "Escuela / CCT";
            emp.cargo = "DIRECTOR DE CENTRO DE TRABAJO (CCT)";
        } else {
            emp.cct = (emp.rfc != null && !emp.rfc.isBlank()) ? emp.rfc : ("RESP-" + emp.idEmpleado);
            emp.nombreEscuela = "PADRÓN DE RESPONSABLES SETAB";
            emp.area = "Resguardante Patrimonial";
            emp.cargo = "RESGUARDANTE PATRIMONIAL SETAB";
        }
        return emp;
    }

    public Empleado buscarResponsablePorIdRelacional(Long idSolicitado) {
        if (idSolicitado == null) {
            return null;
        }
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        if (origen == null) {
            return null;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            if (idSolicitado >= 900_000L) {
                long idCct = idSolicitado - 900_000L;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id_cct, clave_ct, nombre_ct, director, status, activo FROM inventario.cct WHERE id_cct = ? LIMIT 1"
                )) {
                    ps.setLong(1, idCct);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String claveCt = rs.getString("clave_ct");
                            String claveUp = claveCt != null ? claveCt.trim().toUpperCase(Locale.ROOT) : ("CCT-" + idCct);
                            String nombreCt = rs.getString("nombre_ct");
                            String director = rs.getString("director");
                            Empleado empCct = new Empleado();
                            empCct.idEmpleado = idSolicitado;
                            empCct.cct = claveUp;
                            empCct.nombreEscuela = (nombreCt != null && !nombreCt.isBlank()) ? nombreCt.trim() : ("ESCUELA " + claveUp);
                            empCct.nombre = (director != null && !director.isBlank())
                                    ? director.trim().toUpperCase(Locale.ROOT)
                                    : ("TITULAR CCT " + claveUp + " - " + empCct.nombreEscuela);
                            empCct.rfc = claveUp;
                            empCct.claveResponsable = claveUp;
                            empCct.numeroEmpleado = claveUp;
                            empCct.usuario = claveUp;
                            empCct.area = "Escuela / CCT";
                            empCct.cargo = "DIRECTOR DE CENTRO DE TRABAJO (CCT)";
                            empCct.activo = 1;
                            return empCct;
                        }
                    }
                }
                return null;
            }
            String sql = "SELECT r.id_responsable, r.rfc, r.rfc2, r.curp, r.nombres, r.paterno, r.materno, r.apellidos, r.activo, "
                    + "c.clave_ct, c.nombre_ct, 1 AS prioridad "
                    + "FROM inventario.responsables r "
                    + "LEFT JOIN ( "
                    + "    SELECT DISTINCT ON (id_responsable) id_responsable, id_cct "
                    + "    FROM inventario.inventario "
                    + "    WHERE id_responsable IS NOT NULL AND id_cct IS NOT NULL "
                    + ") inv ON inv.id_responsable = r.id_responsable "
                    + "LEFT JOIN inventario.cct c ON c.id_cct = inv.id_cct "
                    + "WHERE r.id_responsable = ? LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, idSolicitado);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return construirEmpleadoDesdeJoinResultSet(rs);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public Empleado crearResponsable(Empleado emp) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "responsables");
            Map<String, Object> m = mapearCamposResponsable(meta, emp);
            if (meta.pkColumna != null && !meta.pkAutoincremental && !"rfc".equalsIgnoreCase(meta.pkColumna)) {
                Integer sqlType = meta.tipoSqlColumnas.get(meta.pkColumna);
                if (sqlType != null && (sqlType == Types.INTEGER || sqlType == Types.BIGINT || sqlType == Types.SMALLINT)) {
                    long sigId = obtenerSiguienteIdEntero(conn, meta.nombreReal, meta.pkColumna);
                    m.put(meta.pkColumna, sigId);
                    emp.idEmpleado = sigId;
                }
            }
            ejecutarInsertGenerico(conn, meta, m, idGen -> emp.idEmpleado = idGen);
            if (emp.idEmpleado == null) {
                emp.idEmpleado = System.currentTimeMillis() % 1000000L;
            }
            cacheResponsables = null;
            cacheIndiceDirectores = null;
            cacheCentrosTrabajo = null;
            return emp;
        }
    }

    public Empleado actualizarResponsable(Long id, Empleado emp) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "responsables");
            Map<String, Object> m = mapearCamposResponsable(meta, emp);
            if (meta.pkColumna != null && !"rfc".equalsIgnoreCase(meta.pkColumna)) {
                m.remove(meta.pkColumna);
                ejecutarUpdateGenerico(conn, meta, m, id);
            } else if (emp.rfc != null && meta.columnas.contains("rfc")) {
                ejecutarUpdatePorColumnaTexto(conn, meta, m, "rfc", emp.rfc);
            }
            emp.idEmpleado = id;
            cacheResponsables = null;
            cacheIndiceDirectores = null;
            cacheCentrosTrabajo = null;
            return emp;
        }
    }

    private Map<String, Object> mapearCamposResponsable(TablaMeta meta, Empleado emp) {
        Map<String, Object> m = new LinkedHashMap<>();
        String rfc = emp.rfc != null ? emp.rfc : emp.claveResponsable;
        ponerTexto(meta, m, "rfc", rfc);
        ponerTexto(meta, m, "rfc2", emp.claveResponsable != null ? emp.claveResponsable : rfc);
        ponerTexto(meta, m, "curp", emp.curp);
        ponerTexto(meta, m, "paterno", emp.apellidoPaterno);
        ponerTexto(meta, m, "materno", emp.apellidoMaterno);
        String apellidos = ((emp.apellidoPaterno != null ? emp.apellidoPaterno : "") + " " + (emp.apellidoMaterno != null ? emp.apellidoMaterno : "")).trim();
        ponerTexto(meta, m, "apellidos", !apellidos.isEmpty() ? apellidos : emp.nombre);
        ponerTexto(meta, m, "nombres", emp.nombre);
        if (meta.columnas.contains("activo")) {
            m.put("activo", emp.activo != null ? emp.activo : 1);
        }
        return m;
    }

    // =========================================================================
    // 4. CRUD DE LOS 14 CATÁLOGOS DEL ESQUEMA "inventario" (<-> CatalogoGeneral)
    // =========================================================================

    public List<CatalogoGeneral> listarTodosCatalogosDiagrama() throws SQLException {
        CacheEntry<List<CatalogoGeneral>> cached = cacheTodosCatalogos;
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        List<CatalogoGeneral> acumulado = new ArrayList<>();
        String[] tiposDiagrama = {
            "proveedores", "partidas", "tipos_articulos", "naturaleza_gasto",
            "marcas", "tipos_folios", "departamentos", "unidades",
            "subdirecciones", "giros", "municipios", "localidades",
            "vialidades", "asentamientos"
        };
        for (String t : tiposDiagrama) {
            if (puedeOperarCatalogo(t)) {
                List<CatalogoGeneral> porTipo = listarCatalogoPorTipo(t);
                if (porTipo.size() > 200) {
                    acumulado.addAll(porTipo.subList(0, 200));
                } else {
                    acumulado.addAll(porTipo);
                }
            }
        }
        cacheTodosCatalogos = new CacheEntry<>(acumulado, TTL_CACHE_MS);
        return acumulado;
    }

    public List<CatalogoGeneral> listarCatalogoPorTipo(String tipoCatalogo) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return Collections.emptyList();
        }
        String tipoNorm = tipoCatalogo.trim().toLowerCase(Locale.ROOT);
        CacheEntry<List<CatalogoGeneral>> cached = cacheCatalogoPorTipo.get(tipoNorm);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        String[] candidatos = candidatosTablaCatalogo(tipoNorm);
        if (candidatos.length == 0) {
            return Collections.emptyList();
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, candidatos);
            if (meta == null) {
                return Collections.emptyList();
            }
            List<CatalogoGeneral> lista = new ArrayList<>();
            String orderByCol = meta.pkColumna != null ? columnaSqlSegura(meta, meta.pkColumna) : "1";
            String sql = "SELECT * FROM " + meta.nombreReal + " ORDER BY " + orderByCol + " ASC LIMIT 10000";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                long idx = 1L;
                while (rs.next()) {
                    CatalogoGeneral c = new CatalogoGeneral();
                    c.tipoCatalogo = tipoNorm;
                    Long pkVal = meta.pkColumna != null ? leerLong(rs, meta, meta.pkColumna) : null;
                    c.id = pkVal != null ? pkVal : idx++;

                    switch (tipoNorm) {
                        case "proveedores" -> {
                            c.rfc = leerString(rs, meta, "rfc");
                            c.clave = (c.rfc != null && !c.rfc.isBlank()) ? c.rfc : String.valueOf(c.id);
                            c.nombre = leerString(rs, meta, "nombre");
                            c.descripcion = c.nombre;
                            c.direccion = leerString(rs, meta, "direccion");
                            c.colonia = leerString(rs, meta, "colonia");
                            c.ciudad = leerString(rs, meta, "ciudad");
                            c.estado = leerString(rs, meta, "estado");
                            c.telefono = leerString(rs, meta, "telefono");
                            c.giro = leerString(rs, meta, "giro");
                        }
                        case "partidas" -> {
                            c.clave = leerString(rs, meta, "pa_partida");
                            c.descripcion = leerString(rs, meta, "pa_descripcion");
                            c.nombre = c.descripcion;
                            c.cuentaContable = leerString(rs, meta, "capital");
                        }
                        case "tipos_articulos", "tipos" -> {
                            c.tipoCatalogo = "tipos_articulos";
                            c.clave = leerString(rs, meta, "tipos");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.partidaAsociada = leerPrimerTextoNoVacio(rs, meta, "partida", "part_se");
                            c.subclave = leerString(rs, meta, "subclave");
                        }
                        case "naturaleza_gasto", "naturaleza_gastos", "naturaleza_gastos_1" -> {
                            c.tipoCatalogo = "naturaleza_gasto";
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                        }
                        case "marcas" -> {
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                        }
                        case "tipos_folios" -> {
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                        }
                        case "departamentos" -> {
                            String uni = leerString(rs, meta, "unidad");
                            String sub = leerString(rs, meta, "subdirector");
                            String dep = leerString(rs, meta, "departamentos");
                            StringBuilder cveDep = new StringBuilder();
                            if (uni != null && !uni.isBlank()) cveDep.append(uni);
                            if (sub != null && !sub.isBlank()) {
                                if (cveDep.length() > 0) cveDep.append(".");
                                cveDep.append(sub);
                            }
                            if (dep != null && !dep.isBlank()) {
                                if (cveDep.length() > 0) cveDep.append(".");
                                cveDep.append(dep);
                            }
                            c.clave = cveDep.length() > 0 ? cveDep.toString() : String.valueOf(c.id);
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.subclave = sub;
                            c.partidaAsociada = uni;
                        }
                        case "unidades", "unidades_1" -> {
                            c.tipoCatalogo = "unidades";
                            c.clave = leerPrimerTextoNoVacio(rs, meta, "u_unidad", "unidad", "clave");
                            c.descripcion = leerPrimerTextoNoVacio(rs, meta, "u_desc1", "descripcion");
                            c.nombre = c.descripcion;
                        }
                        case "subdirecciones", "subdirector", "subdirector_1" -> {
                            c.tipoCatalogo = "subdirecciones";
                            String u = leerString(rs, meta, "unidad");
                            String s = leerPrimerTextoNoVacio(rs, meta, "subdirector", "sub_director", "clave");
                            c.clave = (u != null && !u.isBlank() && s != null && !s.isBlank()) ? (u + "." + s) : (s != null ? s : String.valueOf(c.id));
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.partidaAsociada = u;
                        }
                        case "giros" -> {
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.subclave = leerString(rs, meta, "ringiro");
                        }
                        case "municipios", "municipio" -> {
                            c.tipoCatalogo = "municipios";
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.subclave = leerString(rs, meta, "urse");
                            c.partidaAsociada = leerString(rs, meta, "rspurse");
                        }
                        case "localidades", "localidad" -> {
                            c.tipoCatalogo = "localidades";
                            c.clave = leerPrimerTextoNoVacio(rs, meta, "localidad", "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                            c.partidaAsociada = leerString(rs, meta, "municipio");
                        }
                        case "vialidades", "validades" -> {
                            c.tipoCatalogo = "vialidades";
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                        }
                        case "asentamientos" -> {
                            c.clave = leerString(rs, meta, "clave");
                            c.descripcion = leerString(rs, meta, "descripcion");
                            c.nombre = c.descripcion;
                        }
                    }
                    lista.add(c);
                }
            }
            cacheCatalogoPorTipo.put(tipoNorm, new CacheEntry<>(lista, TTL_CACHE_MS));
            return lista;
        }
    }

    public List<CatalogoGeneral> buscarEnCatalogoPorTipo(String tipoCatalogo, String termino, int limite) throws SQLException {
        List<CatalogoGeneral> todos = listarCatalogoPorTipo(tipoCatalogo);
        if (todos == null || todos.isEmpty()) {
            return Collections.emptyList();
        }
        int max = limite > 0 ? Math.min(limite, 500) : 60;
        String q = termino != null ? termino.trim().toLowerCase(Locale.ROOT) : "";
        if (q.isEmpty()) {
            return todos.size() > max ? new ArrayList<>(todos.subList(0, max)) : todos;
        }
        List<CatalogoGeneral> exactosOPrefijo = new ArrayList<>();
        List<CatalogoGeneral> parciales = new ArrayList<>();
        for (CatalogoGeneral c : todos) {
            if (c == null) continue;
            String clave = c.clave != null ? c.clave.toLowerCase(Locale.ROOT) : "";
            String desc = c.descripcion != null ? c.descripcion.toLowerCase(Locale.ROOT) : "";
            String nom = c.nombre != null ? c.nombre.toLowerCase(Locale.ROOT) : "";
            String rfc = c.rfc != null ? c.rfc.toLowerCase(Locale.ROOT) : "";
            String sub = c.subclave != null ? c.subclave.toLowerCase(Locale.ROOT) : "";
            String part = c.partidaAsociada != null ? c.partidaAsociada.toLowerCase(Locale.ROOT) : "";

            if (clave.startsWith(q) || rfc.startsWith(q) || desc.startsWith(q) || nom.startsWith(q) || sub.startsWith(q)) {
                exactosOPrefijo.add(c);
                if (exactosOPrefijo.size() >= max) break;
            } else if (clave.contains(q) || desc.contains(q) || nom.contains(q) || rfc.contains(q) || sub.contains(q) || part.contains(q)) {
                if (exactosOPrefijo.size() + parciales.size() < max * 2) {
                    parciales.add(c);
                }
            }
        }
        List<CatalogoGeneral> resultado = new ArrayList<>(exactosOPrefijo);
        for (CatalogoGeneral p : parciales) {
            if (resultado.size() >= max) break;
            resultado.add(p);
        }
        return resultado;
    }

    public CatalogoGeneral crearItemCatalogo(String tipoCatalogo, CatalogoGeneral item) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        String tipoNorm = tipoCatalogo.trim().toLowerCase(Locale.ROOT);
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, candidatosTablaCatalogo(tipoNorm));
            Map<String, Object> m = mapearCamposCatalogo(meta, tipoNorm, item, true);
            if (meta.pkColumna != null && !meta.pkAutoincremental) {
                long sigId = obtenerSiguienteIdEntero(conn, meta.nombreReal, meta.pkColumna);
                m.put(meta.pkColumna, sigId);
                item.id = sigId;
            }
            ejecutarInsertGenerico(conn, meta, m, idGen -> item.id = idGen);
            item.tipoCatalogo = tipoNorm;
            invalidarCacheCatalogo(tipoNorm);
            return item;
        }
    }

    public CatalogoGeneral actualizarItemCatalogo(String tipoCatalogo, Long id, CatalogoGeneral item) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        String tipoNorm = tipoCatalogo.trim().toLowerCase(Locale.ROOT);
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, candidatosTablaCatalogo(tipoNorm));
            Map<String, Object> m = mapearCamposCatalogo(meta, tipoNorm, item, false);
            m.remove(meta.pkColumna);
            ejecutarUpdateGenerico(conn, meta, m, id);
            item.id = id;
            item.tipoCatalogo = tipoNorm;
            invalidarCacheCatalogo(tipoNorm);
            return item;
        }
    }

    private void invalidarCacheCatalogo(String tipoNorm) {
        cacheCatalogoPorTipo.remove(tipoNorm);
        cacheTodosCatalogos = null;
        cacheMapasSimples.clear();
    }

    private Map<String, Object> mapearCamposCatalogo(TablaMeta meta, String tipoNorm, CatalogoGeneral item, boolean esAlta) {
        Map<String, Object> m = new LinkedHashMap<>();
        String desc = item.descripcion != null ? item.descripcion : item.nombre;
        switch (tipoNorm) {
            case "proveedores" -> {
                ponerTexto(meta, m, "rfc", item.rfc != null ? item.rfc : item.clave);
                ponerTexto(meta, m, "nombre", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
                ponerTexto(meta, m, "direccion", item.direccion);
                ponerTexto(meta, m, "colonia", item.colonia);
                ponerTexto(meta, m, "ciudad", item.ciudad);
                ponerTexto(meta, m, "estado", item.estado);
                ponerTexto(meta, m, "telefono", item.telefono);
                if (esAlta) ponerFecha(meta, m, "fechaalta", LocalDate.now());
                ponerTexto(meta, m, "giro", item.giro);
            }
            case "partidas" -> {
                ponerTexto(meta, m, "tipo", "1");
                ponerTexto(meta, m, "capital", item.cuentaContable != null ? item.cuentaContable : "S");
                ponerTexto(meta, m, "pa_partida", item.clave);
                ponerTexto(meta, m, "pa_descripcion", desc);
            }
            case "tipos_articulos", "tipos" -> {
                ponerTexto(meta, m, "part_se", item.partidaAsociada);
                ponerTexto(meta, m, "tipos", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                ponerTexto(meta, m, "partida", item.partidaAsociada);
                ponerTexto(meta, m, "subclave", item.subclave);
                ponerTexto(meta, m, "descripcion_subc", desc);
                if (esAlta) {
                    ponerFecha(meta, m, "fecusr", LocalDate.now());
                    ponerTexto(meta, m, "horusr", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
                }
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "naturaleza_gasto", "naturaleza_gastos", "naturaleza_gastos_1" -> {
                ponerTexto(meta, m, "clave", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
            }
            case "marcas", "tipos_folios" -> {
                ponerTexto(meta, m, "clave", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                if (meta.columnas.contains("estatus")) m.put("estatus", 1);
            }
            case "departamentos" -> {
                ponerTexto(meta, m, "unidad", item.partidaAsociada != null ? item.partidaAsociada : "01");
                ponerTexto(meta, m, "subdirector", item.subclave != null ? item.subclave : "01");
                ponerTexto(meta, m, "departamentos", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "unidades", "unidades_1" -> {
                ponerTexto(meta, m, "u_unidad", item.clave);
                ponerTexto(meta, m, "u_desc1", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "subdirecciones", "subdirector", "subdirector_1" -> {
                ponerTexto(meta, m, "unidad", item.partidaAsociada != null ? item.partidaAsociada : "01");
                ponerTexto(meta, m, "subdirector", item.clave);
                ponerTexto(meta, m, "sub_director", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "giros" -> {
                ponerTexto(meta, m, "clave", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                ponerTexto(meta, m, "ringiro", item.subclave);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "municipios", "municipio" -> {
                ponerTexto(meta, m, "clave", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                ponerTexto(meta, m, "urse", item.subclave);
                ponerTexto(meta, m, "rspurse", item.partidaAsociada);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "localidades", "localidad" -> {
                ponerTexto(meta, m, "municipio", item.partidaAsociada != null ? item.partidaAsociada : "04");
                ponerTexto(meta, m, "localidad", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
            case "vialidades", "validades", "asentamientos" -> {
                ponerTexto(meta, m, "clave", item.clave);
                ponerTexto(meta, m, "descripcion", desc);
                if (meta.columnas.contains("activo")) m.put("activo", 1);
            }
        }
        return m;
    }

    // =========================================================================
    // UTILIDADES SQL Y RESOLUCIÓN DE LLAVES FORÁNEAS
    // =========================================================================

    private interface IdCallback {
        void onId(Long id);
    }

    private String columnaSqlSegura(TablaMeta meta, String col) {
        String real = meta.nombreRealColumna.getOrDefault(col.toLowerCase(Locale.ROOT), col);
        if (real.contains(" ") || real.matches("^[0-9].*")) {
            return "\"" + real.replace("\"", "\"\"") + "\"";
        }
        return real;
    }

    private void ejecutarInsertGenerico(Connection conn, TablaMeta meta, Map<String, Object> valores, IdCallback callback) throws SQLException {
        List<String> cols = new ArrayList<>(valores.keySet());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(meta.nombreReal).append(" (");
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                ph.append(", ");
            }
            sql.append(columnaSqlSegura(meta, cols.get(i)));
            ph.append("?");
        }
        sql.append(") VALUES (").append(ph).append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < cols.size(); i++) {
                asignarParametroSql(ps, i + 1, valores.get(cols.get(i)));
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs != null && rs.next()) {
                    long gen = rs.getLong(1);
                    if (gen > 0) callback.onId(gen);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void ejecutarUpdateGenerico(Connection conn, TablaMeta meta, Map<String, Object> valores, Long id) throws SQLException {
        if (valores.isEmpty() || meta.pkColumna == null) return;
        List<String> cols = new ArrayList<>(valores.keySet());
        StringBuilder sql = new StringBuilder("UPDATE ").append(meta.nombreReal).append(" SET ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columnaSqlSegura(meta, cols.get(i))).append(" = ?");
        }
        sql.append(" WHERE ").append(columnaSqlSegura(meta, meta.pkColumna)).append(" = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String col : cols) {
                asignarParametroSql(ps, idx++, valores.get(col));
            }
            ps.setObject(idx, id);
            ps.executeUpdate();
        }
    }

    private void ejecutarUpdatePorColumnaTexto(Connection conn, TablaMeta meta, Map<String, Object> valores, String colWhere, String valWhere) throws SQLException {
        if (valores.isEmpty()) return;
        List<String> cols = new ArrayList<>(valores.keySet());
        StringBuilder sql = new StringBuilder("UPDATE ").append(meta.nombreReal).append(" SET ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columnaSqlSegura(meta, cols.get(i))).append(" = ?");
        }
        sql.append(" WHERE UPPER(TRIM(").append(columnaSqlSegura(meta, colWhere)).append(")) = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String col : cols) {
                asignarParametroSql(ps, idx++, valores.get(col));
            }
            ps.setString(idx, valWhere.trim().toUpperCase(Locale.ROOT));
            ps.executeUpdate();
        }
    }

    private Integer resolverFkEnTabla(
            Connection conn,
            MultiDataSourceConfig.OrigenDatos origen,
            String tabla,
            String pkCol,
            String[] colsBusqueda,
            String valorBuscado
    ) {
        if (valorBuscado == null || valorBuscado.isBlank()) {
            return null;
        }
        try {
            TablaMeta meta = inspeccionarTabla(conn, origen, tabla, "naturaleza_gastos");
            if (meta == null || !meta.columnas.contains(pkCol.toLowerCase(Locale.ROOT))) {
                return null;
            }
            List<String> colsValidas = new ArrayList<>();
            for (String c : colsBusqueda) {
                if (meta.columnas.contains(c.toLowerCase(Locale.ROOT))) {
                    colsValidas.add(c.toLowerCase(Locale.ROOT));
                }
            }
            if (colsValidas.isEmpty()) {
                return null;
            }
            StringBuilder sql = new StringBuilder("SELECT ").append(columnaSqlSegura(meta, pkCol)).append(" FROM ").append(meta.nombreReal).append(" WHERE ");
            for (int i = 0; i < colsValidas.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("UPPER(TRIM(CAST(").append(columnaSqlSegura(meta, colsValidas.get(i))).append(" AS TEXT))) = ?");
            }
            sql.append(" LIMIT 1");
            String limpio = valorBuscado.trim().toUpperCase(Locale.ROOT);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < colsValidas.size(); i++) {
                    ps.setString(i + 1, limpio);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<Integer, String> cargarMapaSimple(
            Connection conn,
            MultiDataSourceConfig.OrigenDatos origen,
            String tabla,
            String pkCol,
            String valorCol
    ) {
        String cacheKey = origen.name() + "::" + tabla.toLowerCase(Locale.ROOT) + "::" + pkCol.toLowerCase(Locale.ROOT) + "::" + valorCol.toLowerCase(Locale.ROOT);
        CacheEntry<Map<Integer, String>> cached = cacheMapasSimples.get(cacheKey);
        if (cached != null && cached.vigente()) {
            return cached.valor;
        }
        Map<Integer, String> mapa = new HashMap<>();
        try {
            TablaMeta meta = inspeccionarTabla(conn, origen, tabla, "naturaleza_gastos");
            if (meta == null || !meta.columnas.contains(pkCol.toLowerCase(Locale.ROOT)) || !meta.columnas.contains(valorCol.toLowerCase(Locale.ROOT))) {
                return mapa;
            }
            String sql = "SELECT " + columnaSqlSegura(meta, pkCol) + ", " + columnaSqlSegura(meta, valorCol) + " FROM " + meta.nombreReal;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String val = rs.getString(2);
                    if (val != null) {
                        mapa.put(id, val.trim());
                    }
                }
            }
            cacheMapasSimples.put(cacheKey, new CacheEntry<>(mapa, TTL_CACHE_MS));
        } catch (Exception ignored) {
        }
        return mapa;
    }

    private long obtenerSiguienteIdEntero(Connection conn, String tabla, String pkCol) {
        String sql = "SELECT COALESCE(MAX(" + pkCol + "), 0) + 1 FROM " + tabla;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception ignored) {
        }
        return 1L;
    }

    private long contarFilas(Connection conn, String tabla) {
        String sql = "SELECT COUNT(*) FROM " + tabla;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private void agregarSiExiste(TablaMeta meta, List<String> destino, String... candidatos) {
        for (String c : candidatos) {
            if (meta.columnas.contains(c.toLowerCase(Locale.ROOT))) {
                destino.add(columnaSqlSegura(meta, c.toLowerCase(Locale.ROOT)));
            }
        }
    }

    private void ponerTexto(TablaMeta meta, Map<String, Object> m, String col, String val) {
        String c = col.toLowerCase(Locale.ROOT);
        if (meta.columnas.contains(c) && val != null) {
            m.put(c, truncarSegunMeta(meta, c, val.toUpperCase(Locale.ROOT)));
        }
    }

    private void ponerFecha(TablaMeta meta, Map<String, Object> m, String col, LocalDate val) {
        String c = col.toLowerCase(Locale.ROOT);
        if (meta.columnas.contains(c)) {
            m.put(c, val != null ? Date.valueOf(val) : null);
        }
    }

    private void asignarParametroSql(PreparedStatement ps, int index, Object val) throws SQLException {
        if (val == null) {
            ps.setNull(index, Types.NULL);
        } else if (val instanceof Date d) {
            ps.setDate(index, d);
        } else if (val instanceof LocalDate ld) {
            ps.setDate(index, Date.valueOf(ld));
        } else {
            ps.setObject(index, val);
        }
    }

    private String extraerClaveInicial(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        int idxGuion = t.indexOf(" - ");
        if (idxGuion > 0) {
            return t.substring(0, idxGuion).trim();
        }
        int idxEspacio = t.indexOf(' ');
        if (idxEspacio > 0 && idxEspacio <= 8 && t.substring(0, idxEspacio).matches("[0-9A-Za-z_\\-]+")) {
            return t.substring(0, idxEspacio).trim();
        }
        return t;
    }

    private String leerPrimerTextoNoVacio(ResultSet rs, TablaMeta meta, String... cols) {
        for (String col : cols) {
            String v = leerString(rs, meta, col);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Integer leerPrimerEnteroNoNulo(ResultSet rs, TablaMeta meta, String... cols) {
        for (String col : cols) {
            Integer v = leerEntero(rs, meta, col);
            if (v != null && v != 0) {
                return v;
            }
        }
        return null;
    }

    private LocalDate leerPrimeraFechaNoNula(ResultSet rs, TablaMeta meta, String... cols) {
        for (String col : cols) {
            LocalDate v = leerFecha(rs, meta, col);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private String leerString(ResultSet rs, TablaMeta meta, String col) {
        String key = col.toLowerCase(Locale.ROOT);
        if (!meta.columnas.contains(key)) return null;
        String real = meta.nombreRealColumna.getOrDefault(key, col);
        try {
            String v = rs.getString(real);
            return v != null ? v.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer leerEntero(ResultSet rs, TablaMeta meta, String col) {
        String key = col.toLowerCase(Locale.ROOT);
        if (!meta.columnas.contains(key)) return null;
        String real = meta.nombreRealColumna.getOrDefault(key, col);
        try {
            int v = rs.getInt(real);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private Long leerLong(ResultSet rs, TablaMeta meta, String col) {
        String key = col.toLowerCase(Locale.ROOT);
        if (!meta.columnas.contains(key)) return null;
        String real = meta.nombreRealColumna.getOrDefault(key, col);
        try {
            long v = rs.getLong(real);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private Double leerDouble(ResultSet rs, TablaMeta meta, String col) {
        String key = col.toLowerCase(Locale.ROOT);
        if (!meta.columnas.contains(key)) return null;
        String real = meta.nombreRealColumna.getOrDefault(key, col);
        try {
            double v = rs.getDouble(real);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate leerFecha(ResultSet rs, TablaMeta meta, String col) {
        String key = col.toLowerCase(Locale.ROOT);
        if (!meta.columnas.contains(key)) return null;
        String real = meta.nombreRealColumna.getOrDefault(key, col);
        try {
            Date d = rs.getDate(real);
            return d != null ? d.toLocalDate() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
