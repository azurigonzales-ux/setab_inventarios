package org.acme;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/resguardos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResguardoResource {

    @Inject
    JsonWebToken jwt;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AsignacionRequest {
        public Long idEmpleado;
        public double cantidad; 
        public String firmaEmpleadoBase64; 
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiberarRequest {
        public double cantidad;
        public String firmaBase64; 
        public String latitudGPS;          
        public String longitudGPS; 
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FirmarRequest {
        public String firmaBase64;
        public String latitudGPS;          
        public String longitudGPS; 
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AprobarBajaRequest {
        public String firmaSuperAdminBase64;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PendienteDTO {
        public Long idResguardo;
        public String equipoMarca;
        public String equipoSerie;
        public LocalDate fechaAsignacion;
        public double cantidadAsignada;
        public String nombreAdminAsignador;
        public String estadoResguardo; 
        public String numeroInventario; 
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResguardoExtendidoDTO {
        public Long idResguardo;
        public Long idArticulo;
        public String equipoSerie;
        public String equipoMarca;
        public Long idEmpleado;
        public String nombreEmpleado;
        public String areaDestino;
        public LocalDate fechaAsignacion;
        public double cantidadAsignada;
        public String estadoResguardo; 
        public String numeroInventario; 
    }

    private String obtenerNombreAdmin() {
        if (jwt != null) {
            String fullName = jwt.getClaim("name");
            if (fullName != null && !fullName.trim().isEmpty()) {
                return fullName;
            }
            if (jwt.getName() != null) {
                Usuario adminUser = Usuario.find("upper(username) = ?1", jwt.getName().trim().toUpperCase()).firstResult();
                if (adminUser != null && adminUser.nombreCompleto != null) {
                    return adminUser.nombreCompleto;
                }
                return jwt.getName();
            }
        }
        return "Administrador del Sistema";
    }

    private Empleado obtenerEmpleadoAutenticado() {
        if (jwt == null || jwt.getName() == null || jwt.getName().trim().isEmpty()) {
            return null;
        }
        String uname = jwt.getName().trim().toUpperCase();
        Empleado empleado = Empleado.find("upper(usuario) = ?1 or upper(rfc) = ?1", uname).firstResult();
        if (empleado == null) {
            Usuario u = Usuario.find("upper(username) = ?1", uname).firstResult();
            if (u != null && u.idEmpleado != null) {
                empleado = Empleado.findById(u.idEmpleado);
            }
        }
        return empleado;
    }

    @POST
    @Path("/asignar/{idEquipo}")
    @Transactional
    @RolesAllowed("SUPERADMIN")
    public Response asignar(@PathParam("idEquipo") Long idEquipo, AsignacionRequest request) {
        if (request == null || request.idEmpleado == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Los datos de asignación y el servidor público son obligatorios.\"}")
                    .build();
        }

        Articulo articulo = Articulo.findById(idEquipo, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Empleado empleado = Empleado.findById(request.idEmpleado);

        if (articulo == null || empleado == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"El artículo o el servidor público seleccionado no existe.\"}")
                    .build();
        }

        double stockActual = articulo.cantidadStock != null ? articulo.cantidadStock : 0.0;
        if (Double.isNaN(request.cantidad) || request.cantidad <= 0 || request.cantidad > stockActual) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Stock insuficiente o cantidad inválida. Disponible actual: " + stockActual + "\"}")
                    .build();
        }

        double nuevoStock = Math.max(0.0, stockActual - request.cantidad);
        articulo.cantidadStock = nuevoStock;
        if (articulo.cantidadStock <= 0) {
            articulo.cantidadStock = 0.0;
            articulo.estado = "Agotado";
        }
        articulo.persist();

        Resguardo resguardo = new Resguardo();
        resguardo.articulo = articulo;
        resguardo.empleado = empleado;
        resguardo.fechaAsignacion = LocalDate.now();
        resguardo.cantidadAsignada = request.cantidad;
        resguardo.firmaAdminBase64 = request.firmaEmpleadoBase64; 
        resguardo.firmaEmpleadoBase64 = null; 
        resguardo.latitudGPS = null; 
        resguardo.longitudGPS = null; 
        resguardo.nombreAdminAsignador = obtenerNombreAdmin();
        resguardo.estadoResguardo = "PENDIENTE_FIRMA";
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "ASIGNACION";

        String matricula = (empleado.numeroEmpleado != null && !empleado.numeroEmpleado.trim().isEmpty()) 
                            ? empleado.numeroEmpleado 
                            : "Sin matrícula";

        historial.detallesModificacion = "Se asignó la cantidad de " + request.cantidad + " al empleado: " + empleado.nombre + " (Matrícula: " + matricula + ")";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();

        return Response.status(Response.Status.CREATED).entity(resguardo).build();
    }

    private String sanitizarTextoPdf(String texto) {
        if (texto == null) {
            return "";
        }
        String limpio = texto
                .replace("\u2022", "-")
                .replace("\u2013", "-")
                .replace("\u2014", "-")
                .replace("\u2018", "'")
                .replace("\u2019", "'")
                .replace("\u201C", "\"")
                .replace("\u201D", "\"")
                .replace("\r", " ")
                .replace("\n", " ");
        StringBuilder sb = new StringBuilder(limpio.length());
        for (int i = 0; i < limpio.length(); i++) {
            char c = limpio.charAt(i);
            if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    @GET
    @Path("/empleado/{idEmpleado}")
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response resguardosPorEmpleado(@PathParam("idEmpleado") Long idEmpleado) {
        if (idEmpleado == null) {
            return Response.ok(new ArrayList<>()).build();
        }
        List<Resguardo> resguardos = Resguardo.find("empleado.idEmpleado = ?1", idEmpleado).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : resguardos) {
            if (r == null) continue;
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            if (r.articulo != null) {
                dto.idArticulo = r.articulo.id;
                dto.equipoSerie = r.articulo.numeroSerie;
                dto.equipoMarca = r.articulo.marca;
                dto.numeroInventario = r.articulo.numeroInventario;
            }
            if (r.empleado != null) {
                dto.idEmpleado = r.empleado.idEmpleado;
                dto.nombreEmpleado = r.empleado.nombre;
                dto.areaDestino = r.empleado.area;
            }
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            if (r.estadoResguardo == null || r.estadoResguardo.trim().isEmpty()) {
                dto.estadoResguardo = (r.firmaEmpleadoBase64 == null || r.firmaEmpleadoBase64.trim().isEmpty()) ? "PENDIENTE_FIRMA" : "ACTIVO";
            } else if ("ACTIVO".equalsIgnoreCase(r.estadoResguardo) && (r.firmaEmpleadoBase64 == null || r.firmaEmpleadoBase64.trim().isEmpty())) {
                dto.estadoResguardo = "PENDIENTE_FIRMA";
            } else {
                dto.estadoResguardo = r.estadoResguardo;
            }
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/mis-equipos")
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response misEquipos() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Empleado empleado = obtenerEmpleadoAutenticado();
        if (empleado == null) {
            return Response.ok(new ArrayList<>()).build();
        }

        List<Resguardo> resguardos = Resguardo.find("empleado.idEmpleado = ?1 and firmaEmpleadoBase64 is not null", empleado.idEmpleado).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : resguardos) {
            if (r == null) continue;
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            if (r.articulo != null) {
                dto.idArticulo = r.articulo.id;
                dto.equipoSerie = r.articulo.numeroSerie;
                dto.equipoMarca = r.articulo.marca;
                dto.numeroInventario = r.articulo.numeroInventario;
            }
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo != null ? r.estadoResguardo : "ACTIVO";
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/pendientes-firma")
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response pendientesFirma() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Empleado empleado = obtenerEmpleadoAutenticado();
        if (empleado == null) {
            return Response.ok(new ArrayList<>()).build();
        }

        List<Resguardo> pendientes = Resguardo.find(
                "empleado.idEmpleado = ?1 and (( (firmaEmpleadoBase64 is null or firmaEmpleadoBase64 = '') and (estadoResguardo is null or estadoResguardo = 'ACTIVO' or estadoResguardo = 'PENDIENTE_FIRMA') ) or estadoResguardo = 'BAJA_EN_PROCESO')",
                empleado.idEmpleado
        ).list();
        List<PendienteDTO> lista = new ArrayList<>();
        
        for (Resguardo r : pendientes) {
            if (r == null) continue;
            PendienteDTO dto = new PendienteDTO();
            dto.idResguardo = r.id;
            if (r.articulo != null) {
                dto.equipoMarca = r.articulo.marca;
                dto.equipoSerie = r.articulo.numeroSerie;
                dto.numeroInventario = r.articulo.numeroInventario;
            }
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.nombreAdminAsignador = r.nombreAdminAsignador != null ? r.nombreAdminAsignador : "Almacén Central";
            dto.estadoResguardo = "BAJA_EN_PROCESO".equalsIgnoreCase(r.estadoResguardo) ? "BAJA_EN_PROCESO" : "PENDIENTE_FIRMA";
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/solicitudes-baja")
    @RolesAllowed("SUPERADMIN")
    public Response obtenerSolicitudesBaja() {
        List<Resguardo> pendientes = Resguardo.find("estadoResguardo = ?1", "SOLICITUD_BAJA").list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : pendientes) {
            if (r == null) continue;
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            if (r.articulo != null) {
                dto.idArticulo = r.articulo.id;
                dto.equipoSerie = r.articulo.numeroSerie;
                dto.equipoMarca = r.articulo.marca;
                dto.numeroInventario = r.articulo.numeroInventario;
            }
            if (r.empleado != null) {
                dto.idEmpleado = r.empleado.idEmpleado;
                dto.nombreEmpleado = r.empleado.nombre;
                dto.areaDestino = r.empleado.nombreEscuela != null ? r.empleado.nombreEscuela : r.empleado.area;
            }
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo;
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @PUT
    @Path("/firmar/{idResguardo}")
    @Transactional
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response firmarResguardo(@PathParam("idResguardo") Long idResguardo, FirmarRequest request) {
        if (jwt == null || jwt.getName() == null) return Response.status(Response.Status.UNAUTHORIZED).build();

        Empleado empleado = obtenerEmpleadoAutenticado();
        if (empleado == null) return Response.status(Response.Status.NOT_FOUND).build();

        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Resguardo no encontrado\"}").build();

        if (resguardo.empleado == null || !resguardo.empleado.idEmpleado.equals(empleado.idEmpleado)) {
            return Response.status(Response.Status.FORBIDDEN).entity("{\"error\": \"No tienes permiso para firmar este resguardo\"}").build();
        }

        if (request == null || request.firmaBase64 == null || request.firmaBase64.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\": \"La firma electrónica es requerida\"}").build();
        }

        if (request.latitudGPS == null || request.latitudGPS.trim().isEmpty()
                || request.longitudGPS == null || request.longitudGPS.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"La ubicación GPS es obligatoria por normatividad de auditoría.\"}").build();
        }

        resguardo.firmaEmpleadoBase64 = request.firmaBase64;
        resguardo.latitudGPS = request.latitudGPS.trim();
        resguardo.longitudGPS = request.longitudGPS.trim();
        resguardo.estadoResguardo = "ACTIVO";
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = resguardo.articulo;
        historial.tipoMovimiento = "FIRMA_RECEPCION";
        historial.detallesModificacion = "El Director " + empleado.nombre + " firmó de conformidad la recepción institucional del equipo.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = empleado.usuario; 
        historial.persist();

        return Response.ok("{\"mensaje\": \"Firma registrada correctamente\"}").build();
    }

    @PUT
    @Path("/solicitar-baja/{idResguardo}")
    @Transactional
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response solicitarBaja(@PathParam("idResguardo") Long idResguardo) {
        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        resguardo.estadoResguardo = "SOLICITUD_BAJA";
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = resguardo.articulo;
        historial.tipoMovimiento = "SOLICITUD_BAJA";
        historial.detallesModificacion = "La escuela solicitó la baja/devolución del equipo.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = jwt.getName(); 
        historial.persist();

        return Response.ok("{\"mensaje\": \"Solicitud enviada a Almacén Central\"}").build();
    }

    @PUT
    @Path("/aprobar-baja/{idResguardo}")
    @Transactional
    @RolesAllowed("SUPERADMIN")
    public Response aprobarBaja(@PathParam("idResguardo") Long idResguardo, AprobarBajaRequest request) {
        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        if (request == null || request.firmaSuperAdminBase64 == null || request.firmaSuperAdminBase64.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\": \"Firma del Almacén requerida\"}").build();
        }

        resguardo.estadoResguardo = "BAJA_EN_PROCESO";
        resguardo.firmaSuperAdminBajaBase64 = request.firmaSuperAdminBase64;
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = resguardo.articulo;
        historial.tipoMovimiento = "APROBACION_BAJA";
        historial.detallesModificacion = "Almacén Central autorizó la baja. Pendiente firma de entrega del Director.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();

        return Response.ok("{\"mensaje\": \"Baja aprobada, esperando firma del Director\"}").build();
    }

    @PUT
    @Path("/liberar/{idResguardo}")
    @Transactional
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"}) 
    public Response liberar(@PathParam("idResguardo") Long idResguardo, LiberarRequest request) {
        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        Articulo articulo = resguardo.articulo;

        if (request == null || request.cantidad <= 0 || request.cantidad > resguardo.cantidadAsignada) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"mensaje\": \"La cantidad a devolver es inválida\"}").build();
        }

        if (request.firmaBase64 != null && !request.firmaBase64.trim().isEmpty()) {
            if (request.latitudGPS == null || request.latitudGPS.trim().isEmpty()
                    || request.longitudGPS == null || request.longitudGPS.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"La ubicación GPS es obligatoria por normatividad de auditoría.\"}").build();
            }
            resguardo.firmaDirectorBajaBase64 = request.firmaBase64;
            resguardo.latitudGPS = request.latitudGPS.trim();
            resguardo.longitudGPS = request.longitudGPS.trim();
        }

        if (articulo != null) {
            double stockActual = articulo.cantidadStock != null ? articulo.cantidadStock : 0.0;
            articulo.cantidadStock = stockActual + request.cantidad;
            if ("Agotado".equalsIgnoreCase(articulo.estado) && articulo.cantidadStock > 0) {
                articulo.estado = "Activo";
            }
            articulo.persist();
        }

        resguardo.cantidadAsignada -= request.cantidad;
        resguardo.estadoResguardo = "DEVUELTO";
        resguardo.fechaBaja = LocalDate.now();
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "DEVOLUCION_COMPLETADA";
        historial.detallesModificacion = "Se devolvieron " + request.cantidad + " unidades físicas al almacén.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = jwt != null ? jwt.getName() : "Sistema"; 
        historial.persist();

        return Response.ok("{\"mensaje\": \"Devolución procesada y equipo regresado al stock.\"}").build();
    }

    @GET
    @Path("/pdf-asignacion/{idResguardo}")
    @Produces("application/pdf")
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response generarPdfAsignacion(@PathParam("idResguardo") Long idResguardo) {
        Resguardo resguardo = Resguardo.findById(idResguardo);

        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        String adminNombre = resguardo.nombreAdminAsignador != null ? resguardo.nombreAdminAsignador : "Almacen Central";

        if ("superadmin".equalsIgnoreCase(adminNombre) || "admin".equalsIgnoreCase(adminNombre)) {
            Usuario userDb = Usuario.find("upper(username) = ?1", adminNombre.trim().toUpperCase()).firstResult();
            if (userDb != null && userDb.nombreCompleto != null) {
                adminNombre = userDb.nombreCompleto;
            }
        }

        String nombreEmpleado = (resguardo.empleado != null && resguardo.empleado.nombre != null)
                ? resguardo.empleado.nombre : "Servidor Publico SETAB";
        String areaEmpleado = (resguardo.empleado != null && resguardo.empleado.area != null)
                ? resguardo.empleado.area : "Centro Educativo";
        String marcaArticulo = (resguardo.articulo != null && resguardo.articulo.marca != null)
                ? resguardo.articulo.marca : "N/A";
        String serieArticulo = (resguardo.articulo != null && resguardo.articulo.numeroSerie != null)
                ? resguardo.articulo.numeroSerie : "N/A";

        long consecutivoActa = Math.max(1L, Resguardo.count("id <= ?1", resguardo.id));
        String folioActa = String.format("%05d", consecutivoActa);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-MX"));
        String fechaActa = (resguardo.fechaAsignacion != null) ? resguardo.fechaAsignacion.format(formatter) : LocalDate.now().format(formatter);

        try (PDDocument document = new PDDocument(); 
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 16);
                contentStream.newLineAtOffset(150, 720);
                contentStream.showText("ACTA OFICIAL DE ASIGNACION DE EQUIPO");
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.newLineAtOffset(400, 680);
                contentStream.showText("Folio: " + sanitizarTextoPdf(folioActa));
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Fecha: " + sanitizarTextoPdf(fechaActa));
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.setLeading(18f); 
                contentStream.newLineAtOffset(50, 610);

                contentStream.showText("A quien corresponda:");
                contentStream.newLine();
                contentStream.newLine();
                contentStream.showText("Por medio de la presente, se hace constar oficialmente que el servidor publico");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(sanitizarTextoPdf(nombreEmpleado) + ",");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" adscrito al area de " + sanitizarTextoPdf(areaEmpleado) + ", recibe de ");
                contentStream.newLine();
                contentStream.showText("conformidad y asume el resguardo del siguiente material/equipo institucional:");
                contentStream.newLine();
                contentStream.newLine();

                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     - Descripcion / Marca: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(sanitizarTextoPdf(marcaArticulo));
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     - Numero de Serie: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(sanitizarTextoPdf(serieArticulo));
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     - Cantidad Asignada: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(sanitizarTextoPdf(String.valueOf(resguardo.cantidadAsignada)));
                contentStream.newLine();
                contentStream.newLine();

                contentStream.showText("El servidor publico se compromete a salvaguardar y utilizar el equipo descrito");
                contentStream.newLine();
                contentStream.showText("estrictamente para el cumplimiento de sus labores oficiales. El administrador ");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(sanitizarTextoPdf(adminNombre));
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" certifica la entrega del mismo.");
                contentStream.endText();

                int firmaY = 250; 

                if (resguardo.firmaAdminBase64 != null && resguardo.firmaAdminBase64.contains(",")) {
                    try {
                        String base64Image = resguardo.firmaAdminBase64.split(",")[1];
                        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                        PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "firmaAdmin");
                        contentStream.drawImage(pdImage, 80, firmaY + 5, 120, 60); 
                    } catch (Exception e) { }
                }

                if (resguardo.firmaEmpleadoBase64 != null && resguardo.firmaEmpleadoBase64.contains(",")) {
                    try {
                        String base64Image = resguardo.firmaEmpleadoBase64.split(",")[1];
                        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                        PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "firmaDirector");
                        contentStream.drawImage(pdImage, 340, firmaY + 5, 120, 60); 
                    } catch (Exception e) { }
                }

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(70, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Entrego (Administrador)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(sanitizarTextoPdf(adminNombre));
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(330, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Recibio (Servidor Publico)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(sanitizarTextoPdf(nombreEmpleado));
                contentStream.endText();

                if (resguardo.latitudGPS != null && resguardo.longitudGPS != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
                    contentStream.newLineAtOffset(50, 100);
                    contentStream.showText(sanitizarTextoPdf("Ubicacion de firma del Servidor Publico (GPS): Latitud " + resguardo.latitudGPS + ", Longitud " + resguardo.longitudGPS));
                    contentStream.endText();
                }
            }

            document.save(baos);
            return Response.ok(baos.toByteArray()).header("Content-Disposition", "attachment; filename=\"Acta_Asignacion_" + folioActa + ".pdf\"").build();

        } catch (Exception e) {
            return Response.serverError().entity("Error al generar PDF: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/pdf-baja/{idResguardo}")
    @Produces("application/pdf")
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response generarPdfBaja(@PathParam("idResguardo") Long idResguardo) {
        Resguardo resguardo = Resguardo.findById(idResguardo);

        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        String adminNombre = resguardo.nombreAdminAsignador != null ? resguardo.nombreAdminAsignador : "Almacen Central";

        if ("superadmin".equalsIgnoreCase(adminNombre) || "admin".equalsIgnoreCase(adminNombre)) {
            Usuario userDb = Usuario.find("upper(username) = ?1", adminNombre.trim().toUpperCase()).firstResult();
            if (userDb != null && userDb.nombreCompleto != null) {
                adminNombre = userDb.nombreCompleto;
            }
        }

        String nombreEmpleado = (resguardo.empleado != null && resguardo.empleado.nombre != null)
                ? resguardo.empleado.nombre : "Servidor Publico SETAB";
        String areaEmpleado = (resguardo.empleado != null && resguardo.empleado.area != null)
                ? resguardo.empleado.area : "Centro Educativo";
        String marcaArticulo = (resguardo.articulo != null && resguardo.articulo.marca != null)
                ? resguardo.articulo.marca : "N/A";
        String serieArticulo = (resguardo.articulo != null && resguardo.articulo.numeroSerie != null)
                ? resguardo.articulo.numeroSerie : "N/A";

        long consecutivoActa = Math.max(1L, Resguardo.count("id <= ?1", resguardo.id));
        String folioActa = String.format("%05d", consecutivoActa);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-MX"));
        String fechaActa = (resguardo.fechaBaja != null) ? resguardo.fechaBaja.format(formatter) : LocalDate.now().format(formatter);

        try (PDDocument document = new PDDocument(); 
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 16);
                contentStream.newLineAtOffset(150, 720);
                contentStream.showText("ACTA OFICIAL DE DEVOLUCION DE EQUIPO");
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.newLineAtOffset(400, 680);
                contentStream.showText("Folio: " + sanitizarTextoPdf(folioActa));
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Fecha: " + sanitizarTextoPdf(fechaActa));
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.setLeading(18f); 
                contentStream.newLineAtOffset(50, 610);

                contentStream.showText("A quien corresponda:");
                contentStream.newLine();
                contentStream.newLine();
                contentStream.showText("Por medio de la presente, se hace constar oficialmente que el servidor publico");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(sanitizarTextoPdf(nombreEmpleado) + ",");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" adscrito al area de " + sanitizarTextoPdf(areaEmpleado) + ", hace entrega y ");
                contentStream.newLine();
                contentStream.showText("devuelve al almacen central el siguiente material/equipo institucional:");
                contentStream.newLine();
                contentStream.newLine();

                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     - Descripcion / Marca: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(sanitizarTextoPdf(marcaArticulo));
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     - Numero de Serie: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(sanitizarTextoPdf(serieArticulo));
                contentStream.newLine();
                contentStream.newLine();

                contentStream.showText("El equipo descrito ha sido recibido, verificado y liberado del resguardo de dicho");
                contentStream.newLine();
                contentStream.showText("servidor publico. El administrador ");
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(sanitizarTextoPdf(adminNombre));
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" certifica");
                contentStream.newLine();
                contentStream.showText("la recepcion del mismo en el sistema.");
                contentStream.endText();

                int firmaY = 250; 

                if (resguardo.firmaDirectorBajaBase64 != null && resguardo.firmaDirectorBajaBase64.contains(",")) {
                    try {
                        String base64Image = resguardo.firmaDirectorBajaBase64.split(",")[1];
                        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                        PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "firmaDirector");
                        contentStream.drawImage(pdImage, 80, firmaY + 5, 120, 60); 
                    } catch (Exception e) {}
                }

                if (resguardo.firmaSuperAdminBajaBase64 != null && resguardo.firmaSuperAdminBajaBase64.contains(",")) {
                    try {
                        String base64Image = resguardo.firmaSuperAdminBajaBase64.split(",")[1];
                        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
                        PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, imageBytes, "firmaAdmin");
                        contentStream.drawImage(pdImage, 340, firmaY + 5, 120, 60); 
                    } catch (Exception e) {}
                }

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(70, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Entrego (Servidor Publico)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(sanitizarTextoPdf(nombreEmpleado));
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(330, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Recibio (Administrador)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(sanitizarTextoPdf(adminNombre));
                contentStream.endText();

                if (resguardo.latitudGPS != null && resguardo.longitudGPS != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
                    contentStream.newLineAtOffset(50, 100);
                    contentStream.showText(sanitizarTextoPdf("Ubicacion de firma del Servidor Publico (GPS): Latitud " + resguardo.latitudGPS + ", Longitud " + resguardo.longitudGPS));
                    contentStream.endText();
                }
            }

            document.save(baos);
            return Response.ok(baos.toByteArray()).header("Content-Disposition", "attachment; filename=\"Acta_Baja_" + folioActa + ".pdf\"").build();

        } catch (Exception e) {
            return Response.serverError().entity("Error interno al generar el acta PDF de devolucion: " + e.getMessage()).build();
        }
    }
}
