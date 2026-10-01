package org.acme;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List; 

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.eclipse.microprofile.jwt.JsonWebToken;

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

    public static class AsignacionRequest {
        public Long idEmpleado;
        public double cantidad; 
        public String firmaEmpleadoBase64; 
    }

    public static class LiberarRequest {
        public double cantidad;
        public String firmaBase64; 
        public String latitudGPS;          
        public String longitudGPS; 
    }

    public static class FirmarRequest {
        public String firmaBase64;
        public String latitudGPS;          
        public String longitudGPS; 
    }

    public static class AprobarBajaRequest {
        public String firmaSuperAdminBase64;
    }

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
                Usuario adminUser = Usuario.find("username", jwt.getName()).firstResult();
                if (adminUser != null && adminUser.nombreCompleto != null) {
                    return adminUser.nombreCompleto;
                }
                return jwt.getName();
            }
        }
        return "Administrador del Sistema";
    }

    @POST
    @Path("/asignar/{idEquipo}")
    @Transactional
    @RolesAllowed("SUPERADMIN")
    public Response asignar(@PathParam("idEquipo") Long idEquipo, AsignacionRequest request) {
        Articulo articulo = Articulo.findById(idEquipo);
        Empleado empleado = Empleado.findById(request.idEmpleado);

        if (articulo == null || empleado == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Artículo o Empleado no existe.").build();
        }

        if (request.cantidad <= 0 || request.cantidad > articulo.cantidadStock) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Cantidad inválida o superior al stock disponible.").build();
        }

        articulo.cantidadStock -= request.cantidad;
        if (articulo.cantidadStock <= 0) {
            articulo.estado = "Agotado";
        }

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
        resguardo.estadoResguardo = "ACTIVO";

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

    @GET
    @Path("/empleado/{idEmpleado}")
    @RolesAllowed("SUPERADMIN")
    public Response resguardosPorEmpleado(@PathParam("idEmpleado") Long idEmpleado) {
        List<Resguardo> resguardos = Resguardo.find("empleado.idEmpleado", idEmpleado).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : resguardos) {
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            dto.idArticulo = r.articulo.id;
            dto.equipoSerie = r.articulo.numeroSerie;
            dto.equipoMarca = r.articulo.marca;
            dto.idEmpleado = r.empleado.idEmpleado;
            dto.nombreEmpleado = r.empleado.nombre;
            dto.areaDestino = r.empleado.area;
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo != null ? r.estadoResguardo : "ACTIVO";
            dto.numeroInventario = r.articulo.numeroInventario; 
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/mis-equipos")
    @RolesAllowed("admin")
    public Response misEquipos() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Empleado empleado = Empleado.find("usuario", jwt.getName()).firstResult();
        if (empleado == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("No se encontró el perfil de la escuela vinculado a este usuario.").build();
        }

        List<Resguardo> resguardos = Resguardo.find("empleado.idEmpleado = ?1 and firmaEmpleadoBase64 is not null", empleado.idEmpleado).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : resguardos) {
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            dto.idArticulo = r.articulo.id;
            dto.equipoSerie = r.articulo.numeroSerie;
            dto.equipoMarca = r.articulo.marca;
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo != null ? r.estadoResguardo : "ACTIVO";
            dto.numeroInventario = r.articulo.numeroInventario; 
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/pendientes-firma")
    @RolesAllowed("admin")
    public Response pendientesFirma() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        Empleado empleado = Empleado.find("usuario", jwt.getName()).firstResult();
        if (empleado == null) return Response.status(Response.Status.NOT_FOUND).build();

        List<Resguardo> pendientes = Resguardo.find("empleado.idEmpleado = ?1 and (firmaEmpleadoBase64 is null or firmaEmpleadoBase64 = '' or estadoResguardo = 'BAJA_EN_PROCESO')", empleado.idEmpleado).list();

        List<PendienteDTO> lista = new ArrayList<>();
        for (Resguardo r : pendientes) {
            PendienteDTO dto = new PendienteDTO();
            dto.idResguardo = r.id;
            dto.equipoMarca = r.articulo.marca;
            dto.equipoSerie = r.articulo.numeroSerie;
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.nombreAdminAsignador = r.nombreAdminAsignador != null ? r.nombreAdminAsignador : "Almacén Central";
            dto.estadoResguardo = r.estadoResguardo;
            dto.numeroInventario = r.articulo.numeroInventario; 
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/solicitudes-baja")
    @RolesAllowed("SUPERADMIN")
    public Response obtenerSolicitudesBaja() {
        List<Resguardo> pendientes = Resguardo.find("estadoResguardo", "SOLICITUD_BAJA").list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : pendientes) {
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            dto.idArticulo = r.articulo.id;
            dto.equipoSerie = r.articulo.numeroSerie;
            dto.equipoMarca = r.articulo.marca;
            dto.idEmpleado = r.empleado.idEmpleado;
            dto.nombreEmpleado = r.empleado.nombre;
            dto.areaDestino = r.empleado.nombreEscuela != null ? r.empleado.nombreEscuela : r.empleado.area;
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo;
            dto.numeroInventario = r.articulo.numeroInventario; 
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @PUT
    @Path("/firmar/{idResguardo}")
    @Transactional
    @RolesAllowed("admin")
    public Response firmarResguardo(@PathParam("idResguardo") Long idResguardo, FirmarRequest request) {
        if (jwt == null || jwt.getName() == null) return Response.status(Response.Status.UNAUTHORIZED).build();

        Empleado empleado = Empleado.find("usuario", jwt.getName()).firstResult();
        if (empleado == null) return Response.status(Response.Status.NOT_FOUND).build();

        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).entity("Resguardo no encontrado").build();

        if (!resguardo.empleado.idEmpleado.equals(empleado.idEmpleado)) {
            return Response.status(Response.Status.FORBIDDEN).entity("No tienes permiso para firmar este resguardo").build();
        }

        if (request.firmaBase64 == null || request.firmaBase64.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("La firma electrónica es requerida").build();
        }

        resguardo.firmaEmpleadoBase64 = request.firmaBase64;
        resguardo.latitudGPS = request.latitudGPS;
        resguardo.longitudGPS = request.longitudGPS;
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
    @RolesAllowed({"admin", "SUPERADMIN"})
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

        if (request.firmaSuperAdminBase64 == null || request.firmaSuperAdminBase64.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Firma del Almacén requerida").build();
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
    @RolesAllowed({"SUPERADMIN", "admin"}) 
    public Response liberar(@PathParam("idResguardo") Long idResguardo, LiberarRequest request) {
        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        Articulo articulo = resguardo.articulo;

        if (request == null || request.cantidad <= 0 || request.cantidad > resguardo.cantidadAsignada) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"mensaje\": \"La cantidad a devolver es inválida\"}").build();
        }

        articulo.cantidadStock += request.cantidad;
        if(articulo.estado.equals("Agotado") && articulo.cantidadStock > 0) {
            articulo.estado = "Activo";
        }

        resguardo.cantidadAsignada -= request.cantidad;

        if(request.firmaBase64 != null && !request.firmaBase64.isEmpty()) {
             resguardo.firmaDirectorBajaBase64 = request.firmaBase64;
        }

        resguardo.estadoResguardo = "DEVUELTO";
        resguardo.fechaBaja = LocalDate.now();
        resguardo.latitudGPS = request.latitudGPS;
        resguardo.longitudGPS = request.longitudGPS;
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "DEVOLUCION_COMPLETADA";
        historial.detallesModificacion = "Se devolvieron " + request.cantidad + " unidades físicas al almacén.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = jwt.getName(); 
        historial.persist();

        return Response.ok("{\"mensaje\": \"Devolución procesada y equipo regresado al stock.\"}").build();
    }

    @GET
    @Path("/pdf-asignacion/{idResguardo}")
    @Produces("application/pdf")
    @RolesAllowed({"SUPERADMIN", "admin"})
    public Response generarPdfAsignacion(@PathParam("idResguardo") Long idResguardo) {
        Resguardo resguardo = Resguardo.findById(idResguardo);

        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        String adminNombre = resguardo.nombreAdminAsignador != null ? resguardo.nombreAdminAsignador : "Almacén Central";

        if ("superadmin".equalsIgnoreCase(adminNombre) || "admin".equalsIgnoreCase(adminNombre)) {
            Usuario userDb = Usuario.find("username", adminNombre).firstResult();
            if (userDb != null && userDb.nombreCompleto != null) {
                adminNombre = userDb.nombreCompleto;
            }
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy");
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
                contentStream.showText("Folio: " + resguardo.id);
                contentStream.newLineAtOffset(0, -15);
                contentStream.showText("Fecha: " + fechaActa);
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
                contentStream.showText(resguardo.empleado.nombre + ",");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" adscrito al area de " + resguardo.empleado.area + ", recibe de ");
                contentStream.newLine();
                contentStream.showText("conformidad y asume el resguardo del siguiente material/equipo institucional:");
                contentStream.newLine();
                contentStream.newLine();

                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     • Descripcion / Marca: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(resguardo.articulo.marca != null ? resguardo.articulo.marca : "N/A");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     • Numero de Serie: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(resguardo.articulo.numeroSerie != null ? resguardo.articulo.numeroSerie : "N/A");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     • Cantidad Asignada: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(String.valueOf(resguardo.cantidadAsignada));
                contentStream.newLine();
                contentStream.newLine();

                contentStream.showText("El servidor publico se compromete a salvaguardar y utilizar el equipo descrito");
                contentStream.newLine();
                contentStream.showText("estrictamente para el cumplimiento de sus labores oficiales. El administrador ");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(adminNombre);
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
                contentStream.showText(adminNombre);
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(330, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Recibio (Servidor Publico)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(resguardo.empleado.nombre);
                contentStream.endText();

                if (resguardo.latitudGPS != null && resguardo.longitudGPS != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
                    contentStream.newLineAtOffset(50, 100);
                    contentStream.showText("Ubicacion de firma del Servidor Publico (GPS): Latitud " + resguardo.latitudGPS + ", Longitud " + resguardo.longitudGPS);
                    contentStream.endText();
                }
            }

            document.save(baos);
            return Response.ok(baos.toByteArray()).header("Content-Disposition", "attachment; filename=\"Acta_Asignacion_" + idResguardo + ".pdf\"").build();

        } catch (Exception e) {
            return Response.serverError().entity("Error al generar PDF: " + e.getMessage()).build();
        }
    }

    @GET
    @Path("/pdf-baja/{idResguardo}")
    @Produces("application/pdf")
    @RolesAllowed({"SUPERADMIN", "admin"})
    public Response generarPdfBaja(@PathParam("idResguardo") Long idResguardo) {
        Resguardo resguardo = Resguardo.findById(idResguardo);

        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).build();

        String adminNombre = resguardo.nombreAdminAsignador != null ? resguardo.nombreAdminAsignador : "Almacén Central";

        if ("superadmin".equalsIgnoreCase(adminNombre) || "admin".equalsIgnoreCase(adminNombre)) {
            Usuario userDb = Usuario.find("username", adminNombre).firstResult();
            if (userDb != null && userDb.nombreCompleto != null) {
                adminNombre = userDb.nombreCompleto;
            }
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy");
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
                contentStream.showText("Fecha: " + fechaActa);
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
                contentStream.showText(resguardo.empleado.nombre + ",");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(" adscrito al area de " + resguardo.empleado.area + ", hace entrega y ");
                contentStream.newLine();
                contentStream.showText("devuelve al almacen central el siguiente material/equipo institucional:");
                contentStream.newLine();
                contentStream.newLine();

                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     • Descripcion / Marca: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(resguardo.articulo.marca != null ? resguardo.articulo.marca : "N/A");
                contentStream.newLine();
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText("     • Numero de Serie: ");
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 12);
                contentStream.showText(resguardo.articulo.numeroSerie != null ? resguardo.articulo.numeroSerie : "N/A");
                contentStream.newLine();
                contentStream.newLine();

                contentStream.showText("El equipo descrito ha sido recibido, verificado y liberado del resguardo de dicho");
                contentStream.newLine();
                contentStream.showText("servidor publico. El administrador ");
                contentStream.setFont(PDType1Font.TIMES_BOLD, 12);
                contentStream.showText(adminNombre);
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
                contentStream.showText(resguardo.empleado.nombre);
                contentStream.endText();

                contentStream.beginText();
                contentStream.setFont(PDType1Font.TIMES_ROMAN, 11);
                contentStream.newLineAtOffset(330, firmaY);
                contentStream.showText("___________________________________");
                contentStream.newLineAtOffset(10, -15);
                contentStream.showText("Recibio (Administrador)");
                contentStream.newLineAtOffset(-10, -15);
                contentStream.setFont(PDType1Font.TIMES_BOLD, 11);
                contentStream.showText(adminNombre);
                contentStream.endText();

                if (resguardo.latitudGPS != null && resguardo.longitudGPS != null) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.TIMES_ROMAN, 9);
                    contentStream.newLineAtOffset(50, 100);
                    contentStream.showText("Ubicacion de firma del Servidor Publico (GPS): Latitud " + resguardo.latitudGPS + ", Longitud " + resguardo.longitudGPS);
                    contentStream.endText();
                }
            }

            document.save(baos);
            return Response.ok(baos.toByteArray()).header("Content-Disposition", "attachment; filename=\"Acta_Baja_" + idResguardo + ".pdf\"").build();

        } catch (Exception e) {
            return Response.serverError().entity("Error interno al generar el acta PDF de devolucion: " + e.getMessage()).build();
        }
    }
}