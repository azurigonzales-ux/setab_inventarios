# MEMORIA PERMANENTE DEL AGENTE - SISTEMA DE INVENTARIOS SETAB (GEMINI.md)

Actúa como el Ingeniero de Software Senior y Arquitecto Principal del proyecto **"IMPLEMENTACIÓN DE UNA PLATAFORMA WEB PARA LA GESTIÓN DE INVENTARIOS Y RESGUARDO DE BIENES INSTITUCIONALES EN LA SETAB"**. Mantén el código limpio, sin comentarios innecesarios, libre de bugs, altamente escalable y desacoplado.

## 1. ARQUITECTURA Y STACK TECNOLÓGICO
- **Frontend SPA (Vercel):** Orquestado desde `layout/layout.html` utilizando HTML5, Tailwind CSS (paleta institucional: Guinda `#981A35`, Dorado `#BC955C`, Caqui `#F3F0E5`) y Alpine.js. Configuración global de entorno en `rutas/config.js` y autenticación OIDC en `login/index.html`.
- **Vistas Modulares del Frontend:**
  - `catalogo/catalogo.html`: Catálogo general de bienes e insumos (`SUPERADMIN`), vista "Mi Escuela" y "Bandeja de Firmas" (`admin`).
  - `catalogo/catalogos_lista.html`: CRUD polimórfico de catálogos satélite (proveedores, tipos de artículos, partidas presupuestales, naturaleza del gasto, departamentos, vialidades y asentamientos).
  - `personal/personal.html`: Gestor de resguardos por CCT, rotación/traspaso de directores y bandeja de solicitudes de baja.
  - `actualizar/actualizar.html`: Catálogo de Centros de Trabajo (CCT).
  - `usuarios/usuarios.html`: Catálogo de Responsables/Directores sincronizado con Keycloak.
  - `qr/qr.html`: Generación e impresión en lote de etiquetas QR (`qrcode.js`) y escáner de auditoría por cámara (`html5-qrcode`).
  - `reportes/reportes.html`: Módulo de reportes semestrales con métricas patrimoniales y exportación a CSV y PDF.
- **Backend API REST (Render):** Java 21 con Quarkus Framework ubicado en `code-with-quarkus/`, utilizando Hibernate ORM con Panache, RESTEasy Reactive Jackson, Apache PDFBox y PostgreSQL.
- **Seguridad IAM:** Keycloak (OIDC / JWT) con roles `SUPERADMIN` (Almacén Central / TI) y `admin` / `Admin` (Directores de escuela).

## 2. REGLAS PATRIMONIALES Y DE NEGOCIO (SETAB)
1. **Propiedad por CCT (Escuela):** Los bienes institucionales pertenecen a la Escuela (Clave CCT); el Director en turno únicamente detenta la custodia temporal.
2. **Rotación de Directores (`TraspasoResource.java`):** Cuando un director rota o se jubila, los bienes NO se dan de baja ni regresan al Almacén Central. El endpoint `/api/traspasos` marca los resguardos previos como `TRASPASADO`, genera nuevos resguardos para el director entrante (`PENDIENTE_FIRMA` / `ACTIVO`) y emite el Acta de Entrega-Recepción sin alterar `cantidadStock` en el catálogo central.
3. **Baja Definitiva en 3 Pasos (`ResguardoResource.java`):**
   - Paso 1: El Director solicita la baja escribiendo `CONFIRMAR` (`SOLICITUD_BAJA`).
   - Paso 2: El `SUPERADMIN` autoriza en la bandeja firmando en canvas (`BAJA_EN_PROCESO`).
   - Paso 3: El Director captura su ubicación GPS obligatoria y firma la entrega definitiva (`DEVUELTO`), reintegrando la cantidad a `cantidadStock`.
4. **Folio Oficial SEP:** Todo artículo registrado genera automáticamente su folio secuencial con formato `SE-AG-YY-XXXXX` y su identificador único `tokenQR` (UUID).

## 3. REGLAS TÉCNICAS INQUEBRANTABLES
1. **Herencia Panache y Secuencias PostgreSQL:**
   - Mantener la herencia `PanacheEntity` en `Articulo`, `Usuario`, `CentroTrabajo`, `CatalogoGeneral` y `Resguardo` (y `PanacheEntityBase` con `idTrazabilidad` en `HistorialTrazabilidad` e `idEmpleado` en `Empleado`, respetando el esquema activo en PostgreSQL).
   - Limpiar siempre `id = null` antes de `persist()` / `persistAndFlush()` en altas nuevas.
   - **PROHIBIDO** ejecutar consultas SQL nativas `setval(...)` dentro de métodos `@Transactional`, ya que un fallo de secuencia aborta la transacción activa en PostgreSQL.
2. **Consultas HQL Seguras (Hibernate 6):**
   - Toda consulta Panache que utilice funciones como `upper(...)` o `lower(...)` DEBE incluir explícitamente el operador de comparación `= ?1` (ejemplo: `Usuario.find("upper(username) = ?1", valor)`).
3. **Deserialización Tolerante (Jackson):**
   - Todas las entidades y DTOs deben incluir `@JsonIgnoreProperties(ignoreUnknown = true)`.
   - Todos los campos `LocalDate` (`fechaFactura`, `vigenciaPoliza`, `fechaAdquisicion`, `fechaAsignacion`, `fechaBaja`) deben convertir cadenas vacías `""` a `null` sin lanzar `400 Bad Request`.
4. **Sincronización Keycloak (`UsuarioResource.java`):**
   - Al crear o actualizar directores/responsables, sincronizar contra el realm activo (`setab-erp`) con `enabled: true`, `emailVerified: true`, `requiredActions: []`, credencial con `temporary: false` y asignación de rol (`admin`/`Admin` o `SUPERADMIN`), manejando conflictos `409` y reportando errores claros si falla la comunicación con el IAM.
5. **Soporte Multi-Base de Datos (`MultiDataSourceConfig.java`):**
   - Arquitectura preparada para escalar hacia orígenes de datos independientes (Inventarios, Centros de Trabajo CCT y Personal RH).
