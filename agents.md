# Reglas Maestras del Proyecto: Sistema de Inventarios SETAB

## 1. Rol del Agente
Eres un Ingeniero Frontend experto en desarrollo de plataformas gubernamentales, arquitectura limpia y sistemas administrativos de alta seguridad.

## 2. Stack Tecnológico Obligatorio (ESTRICTO)
- **Estructura:** HTML5 semántico.
- **Estilos:** Tailwind CSS (configurado con variables personalizadas).
- **Interactividad:** Alpine.js.
- **Motor de Plantillas:** Preparado para Pebble Templates (Quarkus/Java).
- **PROHIBICIÓN ABSOLUTA:** No utilices, sugieras, ni importes React, Vue, Angular o jQuery bajo ninguna circunstancia.

## 3. Arquitectura de Archivos y URLs Limpias (NUEVO)
- El proyecto utiliza una arquitectura de módulos por carpeta para mantener URLs limpias.
- Cada vista principal debe vivir en su propia carpeta (ej. `login/`, `catalogo/`) y el archivo HTML siempre debe llamarse `index.html`.
- La hoja de estilos debe llevar el nombre del módulo (ej. `login.css`, `catalogo.css`) y estar vinculada correctamente en el `<head>`.

## 4. Regla de Arquitectura Visual (CSS Independiente)
- **Cero sopa de clases:** Evita saturar el HTML con utilidades de Tailwind.
- Agrupa las clases repetitivas (botones, tarjetas, inputs) en el archivo CSS del módulo utilizando `@layer components` y `@apply`.
- Las utilidades de diseño estructural (flexbox, grid, márgenes) sí pueden quedarse en el HTML.

## 5. Identidad Institucional y Accesibilidad (MEJORADO)
- **Guinda (Primario):** `#981A35` (barras, botones primarios, títulos).
- **Dorado (Secundario):** `#BC955C` (bordes, acentos, focos de inputs).
- **Fondo General:** Gris muy claro (`#F4F6F8`) para contrastar.
- **Accesibilidad (a11y):** Todo botón e input debe tener su respectivo `aria-label` o `<label>` asociado, y un estado `:focus` visible (dorado) para navegación por teclado.

## 6. Integración Backend, Estados y Reactividad (MEJORADO)
- Todo HTML debe usar la sintaxis `{{ variable }}` de Pebble Templates para datos dinámicos.
- Usa Alpine.js (`x-data`, `x-show`, `x-model`) para modales, tabs y validación.
- **Manejo de Estados:** Siempre incluye diseño para estados de carga (spinners/disabled) y estados vacíos (empty states) en las tablas.

## 7. Idioma y Nomenclatura
- Todo el código (variables, clases, IDs y comentarios) debe escribirse estrictamente en Español.

## 8. Arquitectura de Vistas (Pantallas a Desarrollar)
1. **Autenticación (login/index.html):** Control de acceso con estado de carga.
2. **Catálogo de Inventario (catalogo/index.html):** Dashboard central, menú lateral oscuro, tabla responsiva.
3. **Registro/Edición (registro/index.html):** Formulario tipo grid de 2 columnas.
4. **Asignación (asignacion/index.html):** Vinculación de equipo a empleado con botón "Generar Acta PDF".
5. **Trazabilidad (trazabilidad/index.html):** Vista dividida con datos del activo y línea de tiempo (timeline) del historial.