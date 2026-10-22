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
        public Map<String, String> fkPorTablaDestino = new HashMap<>();
    }

    private final Map<String, TablaMeta> cacheMetadatos = new ConcurrentHashMap<>();

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
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            return inspeccionarTabla(conn, origen, "inventario") != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean puedeOperarCCT() {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        if (origen == null) {
            return false;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            return inspeccionarTabla(conn, origen, "cct") != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean puedeOperarResponsables() {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        if (origen == null) {
            return false;
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            return inspeccionarTabla(conn, origen, "responsables") != null;
        } catch (Exception e) {
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
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            return inspeccionarTabla(conn, origen, candidatos) != null;
        } catch (Exception e) {
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
            default -> new String[0];
        };
    }

    public TablaMeta inspeccionarTabla(Connection conn, MultiDataSourceConfig.OrigenDatos origen, String... posiblesNombres) throws SQLException {
        String[] esquemasBusqueda = new String[]{"Inventario", "inventario", "public", null};
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
                    String colName = rsCols.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                    int colSize = rsCols.getInt("COLUMN_SIZE");
                    int sqlType = rsCols.getInt("DATA_TYPE");
                    String isAuto = rsCols.getString("IS_AUTOINCREMENT");
                    meta.columnas.add(colName);
                    meta.longitudColumnas.put(colName, colSize);
                    meta.tipoSqlColumnas.put(colName, sqlType);
                    if ("YES".equalsIgnoreCase(isAuto) && meta.pkColumna == null) {
                        meta.pkColumna = colName;
                        meta.pkAutoincremental = true;
                    }
                }
            }

            try (ResultSet rsPk = md.getPrimaryKeys(null, meta.schemaReal, nombreEncontrado)) {
                if (rsPk.next()) {
                    meta.pkColumna = rsPk.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                }
            }

            if (meta.pkColumna == null) {
                meta.pkColumna = deducirPkPorConvencion(nombreEncontrado, meta.columnas);
            }

            try (ResultSet rsFk = md.getImportedKeys(null, meta.schemaReal, nombreEncontrado)) {
                while (rsFk.next()) {
                    String pktable = rsFk.getString("PKTABLE_NAME");
                    String fkcol = rsFk.getString("FKCOLUMN_NAME");
                    if (pktable != null && fkcol != null) {
                        meta.fkPorTablaDestino.putIfAbsent(pktable.toLowerCase(Locale.ROOT), fkcol.toLowerCase(Locale.ROOT));
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
                if ("INVENTARIO".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "invofm", "inv_ant", "folio");
                } else if ("SERIE".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "serie");
                } else if ("CCT_RESGUARDANTE".equals(ind) || "RESGUARDANTE_CCT".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "cversp_act", "docto", "folio");
                } else if ("TIPO_PARTIDA".equals(ind)) {
                    agregarSiExiste(metaInv, colsBusqueda, "partida", "natgasto", "tipo_fin");
                } else {
                    agregarSiExiste(metaInv, colsBusqueda, "invofm", "inv_ant", "descripcion", "descripcion1", "marca", "modelo", "serie", "proveedor", "factura", "partida", "cversp_act");
                }
                if (!colsBusqueda.isEmpty()) {
                    where.append(" AND (");
                    for (int i = 0; i < colsBusqueda.size(); i++) {
                        if (i > 0) where.append(" OR ");
                        where.append("LOWER(COALESCE(CAST(").append(colsBusqueda.get(i)).append(" AS TEXT), '')) LIKE ?");
                        params.add(term);
                    }
                    where.append(")");
                }
            }

            if (fondo != null && !fondo.isBlank() && metaInv.columnas.contains("origen")) {
                where.append(" AND UPPER(COALESCE(origen, '')) = ?");
                params.add(fondo.trim().toUpperCase(Locale.ROOT));
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

            ArticuloResource.PaginacionResponse resp = new ArticuloResource.PaginacionResponse();
            resp.articulos = lista;
            resp.totalRegistros = total;
            resp.pageCount = (int) Math.max(1, Math.ceil((double) total / limit));
            return resp;
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
                sql.append("UPPER(COALESCE(CAST(").append(cols.get(i)).append(" AS TEXT), '')) = ?");
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
                        return mapearResultSetAArticulo(
                                rs, metaInv, origen, 1L,
                                mapaCct, mapaPartidas, mapaMarcas, mapaProveedores, mapaTipos, mapaNatGasto, mapaTiposFolios
                        );
                    }
                }
            }
            return null;
        }
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
                sql.append(cols.get(i));
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
                sql.append(cols.get(i)).append(" = ?");
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

        ponerTexto(meta, m, "cversp_act", a.resguardanteActual);
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
        ponerTexto(meta, m, "requis", a.requisicion);
        ponerTexto(meta, m, "salida", a.boletaSalida);
        if (meta.columnas.contains("precio")) {
            Double precio = a.valorInicial != null ? a.valorInicial : a.precioSinIva;
            m.put("precio", precio != null ? precio : 0.0);
        }
        ponerTexto(meta, m, "factura", a.factura);
        ponerTexto(meta, m, "fol_fiscal", a.folioFiscalUuid);
        ponerFecha(meta, m, "fechafact", a.fechaFactura);
        ponerTexto(meta, m, "ofibaja", a.oficioBaja);
        ponerFecha(meta, m, "fecbaja", a.fechaBaja);
        ponerTexto(meta, m, "numacta", a.numeroActa);
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

        // Resolución automática de FKs hacia las 8 tablas de catálogo del Diagrama ER
        if (meta.columnas.contains("fk_cct") && a.centroTrabajo != null && !a.centroTrabajo.isBlank()) {
            Integer fkCct = resolverFkEnTabla(conn, origen, "cct", "id_cct", new String[]{"clave_ct", "nombre_ct"}, a.centroTrabajo);
            if (fkCct != null) m.put("fk_cct", fkCct);
        }
        if (meta.columnas.contains("fk_proveedor") && a.proveedor != null && !a.proveedor.isBlank()) {
            Integer fkProv = resolverFkEnTabla(conn, origen, "proveedores", "id_proveedor", new String[]{"nombre", "rfc"}, a.proveedor);
            if (fkProv != null) m.put("fk_proveedor", fkProv);
        }
        if (meta.columnas.contains("fk_responsable") && a.resguardanteActual != null && !a.resguardanteActual.isBlank()) {
            TablaMeta metaResp = inspeccionarTabla(conn, origen, "responsables");
            if (metaResp != null && metaResp.pkColumna != null) {
                Integer fkResp = resolverFkEnTabla(conn, origen, "responsables", metaResp.pkColumna, new String[]{"rfc", "rfc2", "curp", "nombres"}, a.resguardanteActual);
                if (fkResp != null) m.put("fk_responsable", fkResp);
            }
        }
        if (meta.columnas.contains("fk_marca") && a.marca != null && !a.marca.isBlank()) {
            Integer fkMarca = resolverFkEnTabla(conn, origen, "marcas", "id_marca", new String[]{"descripcion", "clave"}, a.marca);
            if (fkMarca != null) m.put("fk_marca", fkMarca);
        }
        if (meta.columnas.contains("fk_naturalezagastos") && a.naturalezaGasto != null && !a.naturalezaGasto.isBlank()) {
            Integer fkNat = resolverFkEnTabla(conn, origen, "naturaleza_gastos_1", "id_naturaleza_gastos", new String[]{"clave", "descripcion"}, extraerClaveInicial(a.naturalezaGasto));
            if (fkNat != null) m.put("fk_naturalezagastos", fkNat);
        }
        if (meta.columnas.contains("fk_tipofolio") && a.tipoFolioResguardo != null && !a.tipoFolioResguardo.isBlank()) {
            Integer fkTf = resolverFkEnTabla(conn, origen, "tipos_folios", "tipo_folio_id", new String[]{"clave", "descripcion"}, a.tipoFolioResguardo);
            if (fkTf != null) m.put("fk_tipofolio", fkTf);
        }
        if (meta.columnas.contains("fk_tipo") && a.tipoMaterial != null && !a.tipoMaterial.isBlank()) {
            Integer fkTipo = resolverFkEnTabla(conn, origen, "tipos", "id_tipo", new String[]{"tipos", "descripcion", "subclave"}, extraerClaveInicial(a.tipoMaterial));
            if (fkTipo != null) m.put("fk_tipo", fkTipo);
        }
        if (a.partida != null && !a.partida.isBlank()) {
            Integer fkPart = resolverFkEnTabla(conn, origen, "partidas", "id_partida", new String[]{"pa_partida", "pa_descripcion"}, extraerClaveInicial(a.partida));
            if (fkPart != null) {
                String colFkPartida = meta.fkPorTablaDestino.getOrDefault("partidas",
                        meta.columnas.contains("fk_partida") ? "fk_partida" : (meta.columnas.contains("id_partida") ? "id_partida" : null));
                if (colFkPartida != null && meta.columnas.contains(colFkPartida)) {
                    m.put(colFkPartida, fkPart);
                }
                if (meta.columnas.contains("fk_partida_anterior")) {
                    m.putIfAbsent("fk_partida_anterior", fkPart);
                }
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
        if ((a.marca == null || a.marca.isBlank()) && metaInv.columnas.contains("fk_marca")) {
            Integer fk = leerEntero(rs, metaInv, "fk_marca");
            if (fk != null) a.marca = mapaMarcas.get(fk);
        }
        a.modelo = leerString(rs, metaInv, "modelo");
        a.numeroSerie = leerString(rs, metaInv, "serie");
        a.color = leerString(rs, metaInv, "color");
        a.estadoFisico = mapearCodigoAEstadoFisico(leerString(rs, metaInv, "estado"));
        a.proveedor = leerString(rs, metaInv, "proveedor");
        if ((a.proveedor == null || a.proveedor.isBlank()) && metaInv.columnas.contains("fk_proveedor")) {
            Integer fk = leerEntero(rs, metaInv, "fk_proveedor");
            if (fk != null) a.proveedor = mapaProveedores.get(fk);
        }
        a.pedido = leerString(rs, metaInv, "pedido");
        a.requisicion = leerString(rs, metaInv, "requis");
        a.boletaSalida = leerString(rs, metaInv, "salida");
        Double precio = leerDouble(rs, metaInv, "precio");
        if (precio != null) {
            a.valorInicial = precio;
            a.precioSinIva = Math.round((precio / 1.16) * 100.0) / 100.0;
        }
        a.factura = leerString(rs, metaInv, "factura");
        a.folioFiscalUuid = leerString(rs, metaInv, "fol_fiscal");
        a.fechaFactura = leerFecha(rs, metaInv, "fechafact");
        a.oficioBaja = leerString(rs, metaInv, "ofibaja");
        a.fechaBaja = leerFecha(rs, metaInv, "fecbaja");
        a.numeroActa = leerString(rs, metaInv, "numacta");
        a.fechaActa = leerFecha(rs, metaInv, "fecacta");
        a.tipoBaja = leerString(rs, metaInv, "tipo_baja");
        a.carpetaInvestigacion = leerString(rs, metaInv, "carp_inves");
        a.estado = (a.fechaBaja != null || (a.oficioBaja != null && !a.oficioBaja.isBlank())) ? "Baja" : "Activo";

        a.naturalezaGasto = leerString(rs, metaInv, "natgasto");
        if ((a.naturalezaGasto == null || a.naturalezaGasto.isBlank()) && metaInv.columnas.contains("fk_naturalezagastos")) {
            Integer fk = leerEntero(rs, metaInv, "fk_naturalezagastos");
            if (fk != null) a.naturalezaGasto = mapaNatGasto.get(fk);
        }
        a.partida = leerString(rs, metaInv, "partida");
        if (a.partida == null || a.partida.isBlank()) {
            Integer fkPart = leerEntero(rs, metaInv, "fk_partida");
            if (fkPart == null) fkPart = leerEntero(rs, metaInv, "fk_partida_anterior");
            if (fkPart != null) a.partida = mapaPartidas.get(fkPart);
        }
        if (metaInv.columnas.contains("fk_tipo")) {
            Integer fkTipo = leerEntero(rs, metaInv, "fk_tipo");
            if (fkTipo != null) a.tipoMaterial = mapaTipos.get(fkTipo);
        }
        if (metaInv.columnas.contains("fk_cct")) {
            Integer fkCct = leerEntero(rs, metaInv, "fk_cct");
            if (fkCct != null) a.centroTrabajo = mapaCct.get(fkCct);
        }
        a.resguardanteActual = leerString(rs, metaInv, "cversp_act");
        a.cuentaContable = leerString(rs, metaInv, "tipo_fin");
        a.folioResguardo = leerString(rs, metaInv, "folio");
        a.tipoFolioResguardo = leerString(rs, metaInv, "tipofolio");
        if ((a.tipoFolioResguardo == null || a.tipoFolioResguardo.isBlank()) && metaInv.columnas.contains("fk_tipofolio")) {
            Integer fkTf = leerEntero(rs, metaInv, "fk_tipofolio");
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
        a.cantidadStock = 1.0;
        a.categoria = (a.placa != null && !a.placa.isBlank()) ? "Vehiculos" : "Mobiliario";
        a.areaUbicacion = a.centroTrabajo != null ? a.centroTrabajo : "AlmacenGeneral";
        return a;
    }

    // =========================================================================
    // 2. CRUD DE CENTROS DE TRABAJO (cct <-> CentroTrabajo)
    // =========================================================================

    public List<CentroTrabajo> listarCentrosTrabajo() throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenCCTActivo();
        if (origen == null) {
            return Collections.emptyList();
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "cct");
            if (meta == null) {
                return Collections.emptyList();
            }
            List<CentroTrabajo> lista = new ArrayList<>();
            String sql = "SELECT * FROM " + meta.nombreReal + " ORDER BY " + (meta.pkColumna != null ? meta.pkColumna : "1") + " ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                long idx = 1L;
                while (rs.next()) {
                    CentroTrabajo ct = new CentroTrabajo();
                    Long idVal = leerLong(rs, meta, "id_cct");
                    ct.id = idVal != null ? idVal : idx++;
                    ct.claveCT = leerString(rs, meta, "clave_ct");
                    if (ct.claveCT == null || ct.claveCT.isBlank()) {
                        ct.claveCT = leerString(rs, meta, "clave_ct1");
                    }
                    ct.nombreCT = leerString(rs, meta, "nombre_ct");
                    if (ct.nombreCT == null || ct.nombreCT.isBlank()) {
                        ct.nombreCT = leerString(rs, meta, "nomct_ant");
                    }
                    ct.unidadResp = leerString(rs, meta, "unid_responsable");
                    ct.subDireccion = leerString(rs, meta, "subdirector");
                    ct.departamento = leerString(rs, meta, "departamento");
                    ct.nombreVial = leerString(rs, meta, "domicilio");
                    ct.codPostal = leerString(rs, meta, "codpost");
                    ct.municipio = leerString(rs, meta, "municipio");
                    ct.localidad = leerString(rs, meta, "localidad");
                    ct.telefono = leerString(rs, meta, "telefono");
                    ct.turno = leerString(rs, meta, "turno");
                    ct.zonaEscolar = leerString(rs, meta, "zonaescola");
                    ct.sector = leerString(rs, meta, "sector");
                    ct.director = leerString(rs, meta, "director");
                    String st = leerString(rs, meta, "status");
                    Integer act = leerEntero(rs, meta, "activo");
                    ct.estatus = (st != null && !st.isBlank()) ? st : ((act != null && act == 0) ? "B" : "A");
                    lista.add(ct);
                }
            }
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
        ponerTexto(meta, m, "codpost", ct.codPostal);
        ponerTexto(meta, m, "municipio", ct.municipio);
        ponerTexto(meta, m, "localidad", ct.localidad);
        ponerTexto(meta, m, "telefono", ct.telefono);
        ponerTexto(meta, m, "turno", ct.turno);
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
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenResponsablesActivo();
        if (origen == null) {
            return Collections.emptyList();
        }
        try (Connection conn = multiDataSourceConfig.obtenerConexion(origen)) {
            TablaMeta meta = inspeccionarTabla(conn, origen, "responsables");
            if (meta == null) {
                return Collections.emptyList();
            }
            List<Empleado> lista = new ArrayList<>();
            String sql = "SELECT * FROM " + meta.nombreReal + " ORDER BY " + (meta.pkColumna != null ? meta.pkColumna : "1") + " ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                long idx = 1L;
                while (rs.next()) {
                    Empleado emp = new Empleado();
                    Long pkNum = meta.pkColumna != null ? leerLong(rs, meta, meta.pkColumna) : null;
                    emp.idEmpleado = (pkNum != null && pkNum > 0) ? pkNum : idx++;
                    emp.rfc = leerString(rs, meta, "rfc");
                    String rfc2 = leerString(rs, meta, "rfc2");
                    emp.claveResponsable = (rfc2 != null && !rfc2.isBlank()) ? rfc2 : emp.rfc;
                    emp.numeroEmpleado = emp.claveResponsable;
                    emp.curp = leerString(rs, meta, "curp");
                    emp.apellidoPaterno = leerString(rs, meta, "paterno");
                    emp.apellidoMaterno = leerString(rs, meta, "materno");
                    String nombres = leerString(rs, meta, "nombres");
                    String apellidos = leerString(rs, meta, "apellidos");
                    StringBuilder nomCompleto = new StringBuilder();
                    if (nombres != null && !nombres.isBlank()) nomCompleto.append(nombres.trim());
                    if (emp.apellidoPaterno != null && !emp.apellidoPaterno.isBlank()) {
                        if (nomCompleto.length() > 0) nomCompleto.append(" ");
                        nomCompleto.append(emp.apellidoPaterno.trim());
                    }
                    if (emp.apellidoMaterno != null && !emp.apellidoMaterno.isBlank()) {
                        if (nomCompleto.length() > 0) nomCompleto.append(" ");
                        nomCompleto.append(emp.apellidoMaterno.trim());
                    }
                    if (nomCompleto.length() == 0 && apellidos != null && !apellidos.isBlank()) {
                        nomCompleto.append(apellidos.trim());
                    }
                    emp.nombre = nomCompleto.length() > 0 ? nomCompleto.toString() : (emp.rfc != null ? emp.rfc : "RESPONSABLE");
                    Integer act = leerEntero(rs, meta, "activo");
                    emp.activo = act != null ? act : 1;
                    emp.cargo = "RESPONSABLE DE RESGUARDO";
                    lista.add(emp);
                }
            }
            return lista;
        }
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
    // 4. CRUD DE LOS 6 CATÁLOGOS SATÉLITE DEL DIAGRAMA ER (<-> CatalogoGeneral)
    //    proveedores, partidas, tipos, naturaleza_gastos_1, marcas, tipos_folios
    // =========================================================================

    public List<CatalogoGeneral> listarTodosCatalogosDiagrama() throws SQLException {
        List<CatalogoGeneral> acumulado = new ArrayList<>();
        String[] tiposDiagrama = {"proveedores", "partidas", "tipos_articulos", "naturaleza_gasto", "marcas", "tipos_folios"};
        for (String t : tiposDiagrama) {
            if (puedeOperarCatalogo(t)) {
                acumulado.addAll(listarCatalogoPorTipo(t));
            }
        }
        return acumulado;
    }

    public List<CatalogoGeneral> listarCatalogoPorTipo(String tipoCatalogo) throws SQLException {
        MultiDataSourceConfig.OrigenDatos origen = resolverOrigenInventarioActivo(null);
        if (origen == null) {
            return Collections.emptyList();
        }
        String tipoNorm = tipoCatalogo.trim().toLowerCase(Locale.ROOT);
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
            String sql = "SELECT * FROM " + meta.nombreReal + " ORDER BY " + (meta.pkColumna != null ? meta.pkColumna : "1") + " ASC";
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
                            c.partidaAsociada = leerString(rs, meta, "partida");
                            if (c.partidaAsociada == null || c.partidaAsociada.isBlank()) {
                                c.partidaAsociada = leerString(rs, meta, "part_se");
                            }
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
                    }
                    lista.add(c);
                }
            }
            return lista;
        }
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
            return item;
        }
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
        }
        return m;
    }

    // =========================================================================
    // UTILIDADES SQL Y RESOLUCIÓN DE LLAVES FORÁNEAS
    // =========================================================================

    private interface IdCallback {
        void onId(Long id);
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
            sql.append(cols.get(i));
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
            sql.append(cols.get(i)).append(" = ?");
        }
        sql.append(" WHERE ").append(meta.pkColumna).append(" = ?");
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
            sql.append(cols.get(i)).append(" = ?");
        }
        sql.append(" WHERE UPPER(TRIM(").append(colWhere).append(")) = ?");
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
            StringBuilder sql = new StringBuilder("SELECT ").append(pkCol).append(" FROM ").append(meta.nombreReal).append(" WHERE ");
            for (int i = 0; i < colsValidas.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("UPPER(TRIM(CAST(").append(colsValidas.get(i)).append(" AS TEXT))) = ?");
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
        Map<Integer, String> mapa = new HashMap<>();
        try {
            TablaMeta meta = inspeccionarTabla(conn, origen, tabla, "naturaleza_gastos");
            if (meta == null || !meta.columnas.contains(pkCol.toLowerCase(Locale.ROOT)) || !meta.columnas.contains(valorCol.toLowerCase(Locale.ROOT))) {
                return mapa;
            }
            String sql = "SELECT " + pkCol + ", " + valorCol + " FROM " + meta.nombreReal;
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
                destino.add(c.toLowerCase(Locale.ROOT));
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

    private String leerString(ResultSet rs, TablaMeta meta, String col) {
        if (!meta.columnas.contains(col.toLowerCase(Locale.ROOT))) return null;
        try {
            String v = rs.getString(col);
            return v != null ? v.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer leerEntero(ResultSet rs, TablaMeta meta, String col) {
        if (!meta.columnas.contains(col.toLowerCase(Locale.ROOT))) return null;
        try {
            int v = rs.getInt(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private Long leerLong(ResultSet rs, TablaMeta meta, String col) {
        if (!meta.columnas.contains(col.toLowerCase(Locale.ROOT))) return null;
        try {
            long v = rs.getLong(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private Double leerDouble(ResultSet rs, TablaMeta meta, String col) {
        if (!meta.columnas.contains(col.toLowerCase(Locale.ROOT))) return null;
        try {
            double v = rs.getDouble(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate leerFecha(ResultSet rs, TablaMeta meta, String col) {
        if (!meta.columnas.contains(col.toLowerCase(Locale.ROOT))) return null;
        try {
            Date d = rs.getDate(col);
            return d != null ? d.toLocalDate() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
