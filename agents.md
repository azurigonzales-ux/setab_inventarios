# ROL Y ARQUITECTURA MAESTRA - SISTEMA DE INVENTARIOS SETAB

Actúa como el Ingeniero de Software Senior y Arquitecto Principal del proyecto "IMPLEMENTACIÓN DE UNA PLATAFORMA WEB PARA LA GESTIÓN DE INVENTARIOS Y RESGUARDO DE BIENES INSTITUCIONALES EN LA SETAB". Tu objetivo es mantener el código limpio, sin comentarios innecesarios, libre de bugs, altamente escalable y desacoplado.

## 1. STACK TECNOLÓGICO Y ESTRUCTURA DEL REPOSITORIO
- **Frontend (Vercel):** Arquitectura SPA (Single Page Application) orquestada desde `layout/layout.html` con HTML5, Tailwind CSS (colores institucionales: Guinda `#981A35`, Dorado `#BC955C`, Caqui `#F3F0E5`) y Alpine.js.
- **Vistas modulares:**
  - `catalogo/catalogo.html`: Catálogo general de bienes (SUPERADMIN) y vistas de "Mi Escuela" y "Bandeja de Firmas" (admin).
  - `catalogo/catalogos_lista.html`: CRUD polimórfico de catálogos satélite (proveedores, partidas, tipos de artículos, etc.).
  - `personal/personal.html`: Gestor de resguardos por CCT, rotación/traspaso de directores y bandeja de solicitudes de baja.
  - `actualizar/actualizar.html`: Catálogo de Centros de Trabajo (CCT).
  - `usuarios/usuarios.html`: Catálogo de Responsables/Directores sincronizado con Keycloak.
  - `qr/qr.html`: Generación e impresión en lote de etiquetas QR (`qrcode.js`) y escáner de auditoría por cámara (`html5-qrcode`).
  - `reportes/reportes.html`: Módulo de reportes semestrales con métricas patrimoniales y exportación a CSV y PDF.
- **Backend (Render):** Java con Quarkus Framework ubicado en `code-with-quarkus/`, usando Hibernate ORM con Panache y PostgreSQL.
- **Seguridad:** Keycloak (OIDC / JWT) con dos roles operativos: `SUPERADMIN` (Almacén Central / TI) y `admin` / `Admin` (Directores de escuela).

## 2. REGLAS DE NEGOCIO INQUEBRANTABLES (SETAB)
1. **Propiedad por CCT:** Los bienes institucionales pertenecen a la Escuela (CCT); el Director solo tiene la custodia temporal.
2. **Rotación de Directores (Traspasos):** Cuando un director rota o se jubila, NO se dan de baja los bienes en el Almacén Central. Se ejecuta `/api/traspasos` (`TraspasoResource.java`), pasando los resguardos antiguos a `TRASPASADO`, creando nuevos resguardos para el director entrante y generando el Acta de Entrega-Recepción sin alterar `cantidadStock`.
3. **Baja Definitiva en 3 Pasos:** (1) El Director solicita la baja escribiendo `CONFIRMAR` (`SOLICITUD_BAJA`), (2) El `SUPERADMIN` aprueba con su firma en canvas (`BAJA_EN_PROCESO`), (3) El Director captura su GPS obligatorio y firma la entrega definitiva (`DEVUELTO`).
4. **Folio Oficial SEP:** Todo artículo nuevo genera automáticamente el folio `SE-AG-YY-XXXXX` y su `tokenQR` UUID.

## 3. REGLAS TÉCNICAS Y PREVENCIÓN DE ERRORES
1. **Secuencias e IDs en JPA/Panache:**
   - `Articulo`, `Usuario`, `CentroTrabajo`, `CatalogoGeneral`, `Resguardo` e `HistorialTrazabilidad` mantienen su estructura de herencia Panache (`PanacheEntity` / `PanacheEntityBase`) respetando las secuencias existentes en PostgreSQL.
   - `Empleado` hereda de `PanacheEntityBase` con `idEmpleado` (`@JsonProperty("id_empleado")`).
   - NUNCA ejecutes consultas SQL nativas de secuencias (`setval`) que puedan abortar la transacción `@Transactional` en PostgreSQL.
   - Al preparar el sistema para la base de datos normalizada final y soporte multi-base de datos (`MultiDataSourceConfig.java`), mantén intactos los nombres JSON hacia el frontend usando `@Column(name = "...")` y `@JsonProperty`.
2. **Consultas HQL seguras:** En Hibernate 6 / Panache, toda consulta que use funciones como `upper(campo)` DEBE incluir explícitamente el operador `= ?1` (ejemplo: `Usuario.find("upper(username) = ?1", valor)`).
3. **Serialización JSON y Fechas:**
   - Todas las entidades y DTOs deben tener `@JsonIgnoreProperties(ignoreUnknown = true)`.
   - Los campos `LocalDate` (`fechaFactura`, `vigenciaPoliza`, `fechaAdquisicion`) deben convertir cadenas vacías `""` a `null` tanto en el frontend como en el backend para evitar errores `400 Bad Request`, limpiando `id = null` antes de `persistAndFlush()` en `ArticuloResource.java`.
4. **Sincronización Keycloak (`UsuarioResource.java`):**
   - Crear o actualizar usuarios en el realm exacto (`setab-erp`) con `enabled: true`, `emailVerified: true`, `requiredActions: []`, contraseña con `temporary: false`, asignación de rol (`admin`/`Admin` o `SUPERADMIN`), manejo de conflicto `409` por correo duplicado y mensajes de error claros cuando falle la comunicación con Keycloak.
5. **CORS Global:** `CorsFilter.java` debe mantener las cabeceras `Access-Control-Allow-Origin: *` incluso cuando ocurran excepciones internas, devolviendo siempre JSON válido sin saltos de línea crudos.