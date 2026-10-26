package org.acme;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Inject
    PostgresRelacionalCrudService relacionalService;

    private Empleado buscarEmpleadoIntegral(Long idEmpleado) {
        if (idEmpleado == null) {
            return null;
        }
        Empleado local = Empleado.findById(idEmpleado);
        if (local != null) {
            return local;
        }
        if (relacionalService != null && relacionalService.puedeOperarResponsables()) {
            try {
                for (Empleado e : relacionalService.listarResponsables()) {
                    if (e != null && idEmpleado.equals(e.idEmpleado)) {
                        return e;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

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
        public String folioResguardoOficial;
        public String fondoOrigen;
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
        public String folioResguardoOficial;
        public String fondoOrigen;
        public String imagenBienBase64;
        public String descripcionBien;
        public String partidaBien;
        public Double valorBien;
        public String estadoFisicoBien;
        public String facturaBien;
        public LocalDate fechaFacturaBien;
        public String folioFiscalBien;
        public String boletaSalidaBien;
        public LocalDate fechaUltimaActualizacionAnual;
        public String estadoVerificacionAnual;
        public String cct;
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

    private List<Empleado> obtenerEmpleadosVinculadosAlDirector() {
        List<Empleado> resultado = new ArrayList<>();
        if (jwt == null) {
            return resultado;
        }

        java.util.Set<Long> idsAgregados = new java.util.LinkedHashSet<>();
        java.util.Set<String> clavesUsuario = new java.util.LinkedHashSet<>();
        java.util.Set<String> nombresBuscados = new java.util.LinkedHashSet<>();
        java.util.Set<String> cctsBuscados = new java.util.LinkedHashSet<>();

        if (jwt.getName() != null && !jwt.getName().trim().isEmpty()) {
            clavesUsuario.add(jwt.getName().trim().toUpperCase());
        }
        String prefUser = jwt.getClaim("preferred_username");
        if (prefUser != null && !prefUser.trim().isEmpty()) {
            clavesUsuario.add(prefUser.trim().toUpperCase());
        }
        String emailClaim = jwt.getClaim("email");
        if (emailClaim != null && !emailClaim.trim().isEmpty()) {
            clavesUsuario.add(emailClaim.trim().toUpperCase());
        }
        String nameClaim = jwt.getClaim("name");
        if (nameClaim != null && !nameClaim.trim().isEmpty()) {
            nombresBuscados.add(nameClaim.trim().toUpperCase());
        }

        for (String clave : new ArrayList<>(clavesUsuario)) {
            Usuario u = Usuario.find("upper(username) = ?1 or upper(email) = ?1", clave).firstResult();
            if (u != null) {
                if (u.idEmpleado != null) {
                    Empleado empDirecto = Empleado.findById(u.idEmpleado);
                    if (empDirecto != null && idsAgregados.add(empDirecto.idEmpleado)) {
                        resultado.add(empDirecto);
                    }
                }
                if (u.username != null && !u.username.isBlank()) {
                    clavesUsuario.add(u.username.trim().toUpperCase());
                }
                if (u.nombreCompleto != null && !u.nombreCompleto.isBlank()) {
                    nombresBuscados.add(u.nombreCompleto.trim().toUpperCase());
                }
                if (u.cct != null && !u.cct.isBlank()) {
                    String cctLimpio = u.cct.trim().toUpperCase();
                    if (!"CENTRAL".equals(cctLimpio) && !"SIN-CCT".equals(cctLimpio)) {
                        cctsBuscados.add(cctLimpio);
                    }
                }
            }
        }

        for (String nombre : new ArrayList<>(nombresBuscados)) {
            Usuario uNom = Usuario.find("upper(nombreCompleto) = ?1", nombre).firstResult();
            if (uNom != null) {
                if (uNom.idEmpleado != null) {
                    Empleado empDirecto = Empleado.findById(uNom.idEmpleado);
                    if (empDirecto != null && idsAgregados.add(empDirecto.idEmpleado)) {
                        resultado.add(empDirecto);
                    }
                }
                if (uNom.cct != null && !uNom.cct.isBlank()) {
                    String cctLimpio = uNom.cct.trim().toUpperCase();
                    if (!"CENTRAL".equals(cctLimpio) && !"SIN-CCT".equals(cctLimpio)) {
                        cctsBuscados.add(cctLimpio);
                    }
                }
            }
        }

        for (String clave : clavesUsuario) {
            List<Empleado> porClave = Empleado.find(
                    "upper(usuario) = ?1 or upper(rfc) = ?1 or upper(claveResponsable) = ?1",
                    clave
            ).list();
            for (Empleado e : porClave) {
                if (e != null && idsAgregados.add(e.idEmpleado)) {
                    resultado.add(e);
                }
                if (e != null && e.cct != null && !e.cct.isBlank()) {
                    String cctLimpio = e.cct.trim().toUpperCase();
                    if (!"CENTRAL".equals(cctLimpio) && !"SIN-CCT".equals(cctLimpio)) {
                        cctsBuscados.add(cctLimpio);
                    }
                }
            }
        }

        for (String nombre : nombresBuscados) {
            List<Empleado> porNombre = Empleado.find("upper(nombre) = ?1", nombre).list();
            for (Empleado e : porNombre) {
                if (e != null && idsAgregados.add(e.idEmpleado)) {
                    resultado.add(e);
                }
                if (e != null && e.cct != null && !e.cct.isBlank()) {
                    String cctLimpio = e.cct.trim().toUpperCase();
                    if (!"CENTRAL".equals(cctLimpio) && !"SIN-CCT".equals(cctLimpio)) {
                        cctsBuscados.add(cctLimpio);
                    }
                }
            }
        }

        for (Empleado e : new ArrayList<>(resultado)) {
            if (e.cct != null && !e.cct.isBlank()) {
                String cctLimpio = e.cct.trim().toUpperCase();
                if (!"CENTRAL".equals(cctLimpio) && !"SIN-CCT".equals(cctLimpio)) {
                    cctsBuscados.add(cctLimpio);
                }
            }
        }

        for (String cct : cctsBuscados) {
            List<Empleado> porCct = Empleado.find("upper(cct) = ?1", cct).list();
            for (Empleado e : porCct) {
                if (e != null && idsAgregados.add(e.idEmpleado)) {
                    resultado.add(e);
                }
            }
        }

        return resultado;
    }

    private Empleado obtenerEmpleadoAutenticado() {
        List<Empleado> vinculados = obtenerEmpleadosVinculadosAlDirector();
        return vinculados.isEmpty() ? null : vinculados.get(0);
    }

    @Transactional
    protected void sincronizarResguardosHuerfanosParaDirector(List<Empleado> empleadosDirector) {
        if (empleadosDirector == null || empleadosDirector.isEmpty()) {
            return;
        }
        Empleado principal = empleadosDirector.get(0);
        java.util.Set<String> ccts = new java.util.LinkedHashSet<>();
        java.util.Set<String> identificadores = new java.util.LinkedHashSet<>();

        for (Empleado e : empleadosDirector) {
            if (e == null) continue;
            if (e.cct != null && !e.cct.isBlank()) {
                String c = e.cct.trim().toUpperCase();
                if (!"CENTRAL".equals(c) && !"SIN-CCT".equals(c) && !"ALMACÉN CENTRAL".equals(c) && !"ALMACEN CENTRAL".equals(c)) {
                    ccts.add(c);
                }
            }
            if (e.nombre != null && !e.nombre.isBlank()) {
                identificadores.add(e.nombre.trim().toUpperCase());
            }
            if (e.rfc != null && !e.rfc.isBlank()) {
                identificadores.add(e.rfc.trim().toUpperCase());
            }
            if (e.claveResponsable != null && !e.claveResponsable.isBlank()) {
                identificadores.add(e.claveResponsable.trim().toUpperCase());
            }
            if (e.usuario != null && !e.usuario.isBlank()) {
                identificadores.add(e.usuario.trim().toUpperCase());
            }
        }

        java.util.Map<Long, Articulo> candidatos = new java.util.LinkedHashMap<>();
        for (String cct : ccts) {
            List<Articulo> porCct = Articulo.find("upper(centroTrabajo) = ?1", cct).list();
            for (Articulo a : porCct) {
                if (a != null && a.id != null) {
                    candidatos.put(a.id, a);
                }
            }
        }
        for (String ident : identificadores) {
            List<Articulo> porResp = Articulo.find("upper(resguardanteActual) = ?1", ident).list();
            for (Articulo a : porResp) {
                if (a != null && a.id != null) {
                    candidatos.put(a.id, a);
                }
            }
        }

        for (Articulo art : candidatos.values()) {
            if (art == null || "Baja".equalsIgnoreCase(art.estado)) {
                continue;
            }
            long existentesActivos = Resguardo.count(
                    "articulo.id = ?1 and (estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA' or estadoResguardo = 'ACTIVO' or estadoResguardo = 'SOLICITUD_BAJA' or estadoResguardo = 'BAJA_EN_PROCESO')",
                    art.id
            );
            if (existentesActivos > 0) {
                continue;
            }

            String fondo = (art.fondoOrigen != null && !art.fondoOrigen.isBlank())
                    ? art.fondoOrigen.trim().toUpperCase()
                    : "INTERNO";
            String folioOficialNvo;
            if (art.folioResguardo != null && !art.folioResguardo.isBlank()) {
                folioOficialNvo = art.folioResguardo.trim();
            } else {
                long consecutivoFondo = Resguardo.count("upper(fondoOrigen) = ?1", fondo) + 1;
                String anioCorto = String.valueOf(LocalDate.now().getYear()).substring(2);
                folioOficialNvo = String.format("%07d %s-NVO", consecutivoFondo, anioCorto);
                art.folioResguardo = folioOficialNvo;
            }

            if (art.resguardanteActual == null || art.resguardanteActual.isBlank()) {
                art.resguardanteActual = principal.nombre;
            }
            if ((art.centroTrabajo == null || art.centroTrabajo.isBlank()) && principal.cct != null && !principal.cct.isBlank()) {
                art.centroTrabajo = principal.cct.trim().toUpperCase();
            }
            if (art.fechaResguardo == null) {
                art.fechaResguardo = LocalDate.now();
            }
            art.cantidadStock = 0.0;
            art.estado = "Agotado";
            art.persist();

            Resguardo nuevo = new Resguardo();
            nuevo.articulo = art;
            nuevo.empleado = principal;
            nuevo.fechaAsignacion = art.fechaResguardo != null ? art.fechaResguardo : LocalDate.now();
            nuevo.cantidadAsignada = 1.0;
            nuevo.nombreAdminAsignador = "Almacén Central";
            nuevo.estadoResguardo = "PENDIENTE_FIRMA";
            nuevo.fondoOrigen = fondo;
            nuevo.folioResguardoOficial = folioOficialNvo;
            nuevo.persist();
        }
    }

    private void poblarDatosArticuloDTO(ResguardoExtendidoDTO dto, Resguardo r) {
        dto.folioResguardoOficial = r.folioResguardoOficial;
        dto.fondoOrigen = r.fondoOrigen;
        if (r.articulo != null) {
            dto.idArticulo = r.articulo.id;
            dto.equipoSerie = r.articulo.numeroSerie;
            dto.equipoMarca = r.articulo.marca;
            dto.numeroInventario = r.articulo.numeroInventario;
            if (dto.folioResguardoOficial == null || dto.folioResguardoOficial.isBlank()) {
                dto.folioResguardoOficial = r.articulo.folioResguardo;
            }
            if (dto.fondoOrigen == null || dto.fondoOrigen.isBlank()) {
                dto.fondoOrigen = r.articulo.fondoOrigen != null ? r.articulo.fondoOrigen : "INTERNO";
            }
            dto.imagenBienBase64 = r.articulo.imagenBienBase64;
            dto.descripcionBien = r.articulo.descripcion != null && !r.articulo.descripcion.isBlank()
                    ? r.articulo.descripcion : r.articulo.marca;
            dto.partidaBien = r.articulo.partida;
            dto.valorBien = r.articulo.valorInicial != null ? r.articulo.valorInicial : r.articulo.precioSinIva;
            dto.estadoFisicoBien = r.articulo.estadoFisico != null ? r.articulo.estadoFisico : "BUENO";
            dto.facturaBien = r.articulo.factura;
            dto.fechaFacturaBien = r.articulo.fechaFactura;
            dto.folioFiscalBien = r.articulo.folioFiscalUuid;
            dto.boletaSalidaBien = r.articulo.boletaSalida;
            dto.fechaUltimaActualizacionAnual = r.articulo.fechaUltimaActualizacionAnual;
            dto.estadoVerificacionAnual = r.articulo.estadoVerificacionAnual != null ? r.articulo.estadoVerificacionAnual : "PENDIENTE";
            dto.cct = r.articulo.centroTrabajo;
        }
        if (r.empleado != null) {
            dto.idEmpleado = r.empleado.idEmpleado;
            dto.nombreEmpleado = r.empleado.nombre;
            dto.areaDestino = r.empleado.area;
            if (dto.cct == null || dto.cct.isBlank()) {
                dto.cct = r.empleado.cct;
            }
        }
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

        Articulo articulo = relacionalService != null
                ? relacionalService.sincronizarArticuloEnPanache(idEquipo, null)
                : Articulo.findById(idEquipo, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Empleado empleado = relacionalService != null
                ? relacionalService.sincronizarEmpleadoEnPanache(request.idEmpleado)
                : Empleado.findById(request.idEmpleado);

        if (articulo == null || empleado == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"El artículo o el servidor público seleccionado no existe.\"}")
                    .build();
        }

        double stockActual = articulo.cantidadStock != null ? articulo.cantidadStock : 0.0;
        if (stockActual <= 0 && relacionalService != null && relacionalService.puedeOperarInventario(null)) {
            stockActual = Math.max(1.0, request.cantidad);
            articulo.cantidadStock = stockActual;
        }
        if (Double.isNaN(request.cantidad) || request.cantidad <= 0 || request.cantidad > stockActual) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Stock insuficiente o cantidad inválida. Disponible actual: " + stockActual + "\"}")
                    .build();
        }

        String fondo = (articulo.fondoOrigen != null && !articulo.fondoOrigen.isBlank())
                ? articulo.fondoOrigen.trim().toUpperCase()
                : "INTERNO";
        long consecutivoFondo = Resguardo.count("upper(fondoOrigen) = ?1", fondo) + 1;
        String anioCorto = String.valueOf(LocalDate.now().getYear()).substring(2);
        String folioOficialNvo = String.format("%07d %s-NVO", consecutivoFondo, anioCorto);

        double nuevoStock = Math.max(0.0, stockActual - request.cantidad);
        articulo.cantidadStock = nuevoStock;
        if (articulo.cantidadStock <= 0) {
            articulo.cantidadStock = 0.0;
            articulo.estado = "Agotado";
        }
        if (articulo.resguardanteActual != null && !articulo.resguardanteActual.isBlank()
                && (articulo.resguardanteHistorico == null || articulo.resguardanteHistorico.isBlank())) {
            articulo.resguardanteHistorico = articulo.resguardanteActual;
        }
        articulo.resguardanteActual = empleado.nombre;
        String cctAsignada = (empleado.cct != null && !empleado.cct.isBlank())
                ? empleado.cct.trim().toUpperCase()
                : (articulo.centroTrabajo != null ? articulo.centroTrabajo.trim().toUpperCase() : "SIN-CCT");
        articulo.centroTrabajo = cctAsignada;
        articulo.fechaResguardo = LocalDate.now();
        articulo.folioResguardo = folioOficialNvo;
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
        resguardo.fondoOrigen = fondo;
        resguardo.folioResguardoOficial = folioOficialNvo;
        resguardo.persist();

        if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
            String rfcOClave = (empleado.rfc != null && !empleado.rfc.isBlank())
                    ? empleado.rfc
                    : (empleado.claveResponsable != null && !empleado.claveResponsable.isBlank() ? empleado.claveResponsable : empleado.nombre);
            relacionalService.actualizarResguardoEnInventarioRelacional(
                    articulo.numeroInventario,
                    idEquipo,
                    cctAsignada,
                    request.idEmpleado,
                    rfcOClave,
                    folioOficialNvo,
                    "NVO"
            );
        }

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = articulo;
        historial.tipoMovimiento = "ASIGNACION_RESGUARDO";

        String numInv = (articulo.numeroInventario != null && !articulo.numeroInventario.isBlank())
                ? articulo.numeroInventario
                : String.valueOf(articulo.id);

        historial.detallesModificacion = "Asignación de Resguardo [" + fondo + "] | No. Inventario: " + numInv
                + " | Folio Resguardo: " + folioOficialNvo
                + " | CCT: " + cctAsignada
                + " | Director: " + empleado.nombre
                + " | Cantidad: " + request.cantidad;
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = obtenerNombreAdmin(); 
        historial.persist();

        return Response.status(Response.Status.CREATED).entity(resguardo).build();
    }

    @POST
    @Path("/confirmar-actualizacion-anual/{idEmpleado}")
    @Transactional
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response confirmarActualizacionAnual(@PathParam("idEmpleado") Long idEmpleado) {
        if (idEmpleado == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"ID de director/resguardante requerido.\"}")
                    .build();
        }
        Empleado empleado = relacionalService != null
                ? relacionalService.sincronizarEmpleadoEnPanache(idEmpleado)
                : buscarEmpleadoIntegral(idEmpleado);
        if (empleado == null) {
            empleado = buscarEmpleadoIntegral(idEmpleado);
        }
        if (empleado == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Director/resguardante no encontrado.\"}")
                    .build();
        }

        List<Long> idsEmp = new ArrayList<>();
        idsEmp.add(idEmpleado);
        if (empleado.idEmpleado != null && !idsEmp.contains(empleado.idEmpleado)) {
            idsEmp.add(empleado.idEmpleado);
        }

        List<Resguardo> resguardos = new ArrayList<>(Resguardo.find(
                "empleado.idEmpleado in ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo = 'PENDIENTE_FIRMA' or estadoResguardo is null)",
                idsEmp
        ).list());

        if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
            List<Articulo> bienesExt = relacionalService.listarBienesPorResponsableOCct(
                    idEmpleado, empleado.rfc, empleado.cct, empleado.nombre
            );
            for (Articulo ext : bienesExt) {
                if (ext == null) continue;
                Articulo artLocal = relacionalService.sincronizarObjetoArticuloEnPanache(ext, null);
                if (artLocal == null || artLocal.id == null) continue;
                boolean yaTieneResguardo = resguardos.stream()
                        .anyMatch(r -> r != null && r.articulo != null && artLocal.id.equals(r.articulo.id));
                if (!yaTieneResguardo) {
                    Resguardo nuevoResg = new Resguardo();
                    nuevoResg.articulo = artLocal;
                    nuevoResg.empleado = empleado;
                    nuevoResg.fechaAsignacion = artLocal.fechaResguardo != null ? artLocal.fechaResguardo : LocalDate.now();
                    nuevoResg.cantidadAsignada = 1.0;
                    nuevoResg.nombreAdminAsignador = obtenerNombreAdmin();
                    nuevoResg.estadoResguardo = "ACTIVO";
                    nuevoResg.fondoOrigen = artLocal.fondoOrigen != null ? artLocal.fondoOrigen : "OFICIAL";
                    nuevoResg.folioResguardoOficial = artLocal.folioResguardo;
                    nuevoResg.persistAndFlush();
                    resguardos.add(nuevoResg);
                }
            }
        }

        String anioActual = String.valueOf(LocalDate.now().getYear());
        String cct = empleado.cct != null ? empleado.cct.trim().toUpperCase() : "SIN-CCT";
        String rfcOClave = (empleado.rfc != null && !empleado.rfc.isBlank())
                ? empleado.rfc
                : (empleado.claveResponsable != null && !empleado.claveResponsable.isBlank() ? empleado.claveResponsable : empleado.nombre);
        Map<String, String> foliosPorFondo = new HashMap<>();
        int totalVerificados = 0;

        for (Resguardo r : resguardos) {
            if (r == null || r.articulo == null) continue;
            Articulo a = r.articulo;
            String fondo = (a.fondoOrigen != null && !a.fondoOrigen.isBlank())
                    ? a.fondoOrigen.trim().toUpperCase()
                    : (r.fondoOrigen != null && !r.fondoOrigen.isBlank() ? r.fondoOrigen.trim().toUpperCase() : "INTERNO");

            String folioCre = foliosPorFondo.get(fondo);
            if (folioCre == null) {
                long consecutivo = Resguardo.count("upper(fondoOrigen) = ?1", fondo) + totalVerificados + 1;
                folioCre = String.format("%s %05d-CRE", anioActual, consecutivo);
                foliosPorFondo.put(fondo, folioCre);
            }

            String folioAnterior = (r.folioResguardoOficial != null && !r.folioResguardoOficial.isBlank())
                    ? r.folioResguardoOficial
                    : (a.folioResguardo != null ? a.folioResguardo : "S/F");

            r.fondoOrigen = fondo;
            r.folioResguardoOficial = folioCre;
            r.persist();

            a.folioResguardo = folioCre;
            a.fechaUltimaActualizacionAnual = LocalDate.now();
            a.estadoVerificacionAnual = "VERIFICADO";
            a.fechaResguardo = LocalDate.now();
            a.resguardanteActual = empleado.nombre;
            if (cct != null && !cct.equals("SIN-CCT")) {
                a.centroTrabajo = cct;
            }
            a.persist();

            if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
                relacionalService.actualizarResguardoEnInventarioRelacional(
                        a.numeroInventario,
                        null,
                        cct,
                        idEmpleado,
                        rfcOClave,
                        folioCre,
                        "CRE"
                );
            }

            HistorialTrazabilidad h = new HistorialTrazabilidad();
            h.articulo = a;
            h.tipoMovimiento = "ACTUALIZACION_ANUAL_VERIFICADA";
            h.detallesModificacion = "Verificación física anual concluida [" + fondo + "]. No. Inventario: "
                    + (a.numeroInventario != null ? a.numeroInventario : a.id)
                    + " | Folio Anterior: " + folioAnterior
                    + " -> Nuevo Folio Resguardo: " + folioCre
                    + " | CCT: " + cct
                    + " | Resguardante: " + empleado.nombre;
            h.fechaMovimiento = java.time.LocalDateTime.now();
            h.username = obtenerNombreAdmin();
            h.persist();

            totalVerificados++;
        }

        String folioPrincipal = foliosPorFondo.values().stream().findFirst().orElse(anioActual + " 00001-CRE");
        return Response.ok(Map.of(
                "mensaje", "Actualización anual confirmada y verificada oficialmente.",
                "totalVerificados", totalVerificados,
                "folioGenerado", folioPrincipal,
                "foliosPorFondo", foliosPorFondo
        )).build();
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
        Empleado empIntegral = buscarEmpleadoIntegral(idEmpleado);
        List<Long> idsEmp = new ArrayList<>();
        idsEmp.add(idEmpleado);
        if (empIntegral != null) {
            if (empIntegral.rfc != null && !empIntegral.rfc.isBlank()) {
                for (Empleado eLoc : Empleado.<Empleado>find("upper(rfc) = ?1", empIntegral.rfc.trim().toUpperCase(Locale.ROOT)).list()) {
                    if (eLoc.idEmpleado != null && !idsEmp.contains(eLoc.idEmpleado)) {
                        idsEmp.add(eLoc.idEmpleado);
                    }
                }
            }
            if (empIntegral.nombre != null && !empIntegral.nombre.isBlank()) {
                for (Empleado eLoc : Empleado.<Empleado>find("upper(nombre) = ?1", empIntegral.nombre.trim().toUpperCase(Locale.ROOT)).list()) {
                    if (eLoc.idEmpleado != null && !idsEmp.contains(eLoc.idEmpleado)) {
                        idsEmp.add(eLoc.idEmpleado);
                    }
                }
            }
        }
        List<Resguardo> resguardos = Resguardo.find("empleado.idEmpleado in ?1", idsEmp).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();
        java.util.Set<String> inventariosAgregados = new java.util.HashSet<>();

        for (Resguardo r : resguardos) {
            if (r == null) continue;
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            poblarDatosArticuloDTO(dto, r);
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            if (r.estadoResguardo == null || r.estadoResguardo.trim().isEmpty()) {
                dto.estadoResguardo = (r.firmaEmpleadoBase64 == null || r.firmaEmpleadoBase64.trim().isEmpty()) ? "PENDIENTE_FIRMA" : "ACTIVO";
            } else if ("ACTIVO".equalsIgnoreCase(r.estadoResguardo) && (r.firmaEmpleadoBase64 == null || r.firmaEmpleadoBase64.trim().isEmpty())) {
                dto.estadoResguardo = "PENDIENTE_FIRMA";
            } else {
                dto.estadoResguardo = r.estadoResguardo;
            }
            if (dto.numeroInventario != null && !dto.numeroInventario.isBlank()) {
                inventariosAgregados.add(dto.numeroInventario.trim().toUpperCase(Locale.ROOT));
            }
            lista.add(dto);
        }

        if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
            Empleado emp = buscarEmpleadoIntegral(idEmpleado);
            String rfc = emp != null ? emp.rfc : null;
            String cct = emp != null ? emp.cct : null;
            String nombre = emp != null ? emp.nombre : null;
            List<Articulo> bienesRel = relacionalService.listarBienesPorResponsableOCct(idEmpleado, rfc, cct, nombre);
            long synthId = 800_000L;
            for (Articulo a : bienesRel) {
                if (a == null) continue;
                String numInvKey = a.numeroInventario != null ? a.numeroInventario.trim().toUpperCase(Locale.ROOT) : "";
                if (!numInvKey.isEmpty() && !inventariosAgregados.add(numInvKey)) {
                    continue;
                }
                ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
                dto.idResguardo = a.id != null ? a.id : (++synthId);
                dto.idArticulo = a.id;
                dto.equipoSerie = a.numeroSerie;
                dto.equipoMarca = (a.marca != null && !a.marca.isBlank()) ? a.marca : a.descripcion;
                dto.idEmpleado = idEmpleado;
                dto.nombreEmpleado = nombre != null ? nombre : a.resguardanteActual;
                dto.areaDestino = a.areaUbicacion;
                dto.fechaAsignacion = a.fechaResguardo != null ? a.fechaResguardo : a.fechaAdquisicion;
                dto.cantidadAsignada = a.cantidadStock != null ? a.cantidadStock : 1.0;
                dto.estadoResguardo = "Baja".equalsIgnoreCase(a.estado) ? "DEVUELTO" : "ACTIVO";
                dto.numeroInventario = a.numeroInventario;
                dto.folioResguardoOficial = a.folioResguardo;
                dto.fondoOrigen = a.fondoOrigen != null ? a.fondoOrigen : "OFICIAL";
                dto.imagenBienBase64 = a.imagenBienBase64;
                dto.descripcionBien = (a.descripcion != null && !a.descripcion.isBlank()) ? a.descripcion : a.marca;
                dto.partidaBien = a.partida;
                dto.valorBien = a.valorInicial != null ? a.valorInicial : a.precioSinIva;
                dto.estadoFisicoBien = a.estadoFisico != null ? a.estadoFisico : "BUENO";
                dto.facturaBien = a.factura;
                dto.fechaFacturaBien = a.fechaFactura;
                dto.folioFiscalBien = a.folioFiscalUuid;
                dto.boletaSalidaBien = a.boletaSalida;
                dto.fechaUltimaActualizacionAnual = a.fechaUltimaActualizacionAnual;
                dto.estadoVerificacionAnual = a.estadoVerificacionAnual != null ? a.estadoVerificacionAnual : "VERIFICADO";
                dto.cct = (a.centroTrabajo != null && !a.centroTrabajo.isBlank()) ? a.centroTrabajo : cct;
                lista.add(dto);
            }
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/mis-equipos")
    @Transactional
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response misEquipos() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<Empleado> empleados = obtenerEmpleadosVinculadosAlDirector();
        if (empleados.isEmpty()) {
            return Response.ok(new ArrayList<>()).build();
        }

        sincronizarResguardosHuerfanosParaDirector(empleados);

        List<Long> idsEmpleados = new ArrayList<>();
        for (Empleado e : empleados) {
            if (e != null && e.idEmpleado != null) {
                idsEmpleados.add(e.idEmpleado);
            }
        }

        List<Resguardo> resguardos = Resguardo.find(
                "empleado.idEmpleado in ?1 and firmaEmpleadoBase64 is not null and firmaEmpleadoBase64 <> ''",
                idsEmpleados
        ).list();
        List<ResguardoExtendidoDTO> lista = new ArrayList<>();

        for (Resguardo r : resguardos) {
            if (r == null) continue;
            ResguardoExtendidoDTO dto = new ResguardoExtendidoDTO();
            dto.idResguardo = r.id;
            poblarDatosArticuloDTO(dto, r);
            dto.fechaAsignacion = r.fechaAsignacion;
            dto.cantidadAsignada = r.cantidadAsignada;
            dto.estadoResguardo = r.estadoResguardo != null ? r.estadoResguardo : "ACTIVO";
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @GET
    @Path("/pendientes-firma")
    @Transactional
    @RolesAllowed({"admin", "Admin", "SUPERADMIN"})
    public Response pendientesFirma() {
        if (jwt == null || jwt.getName() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        List<Empleado> empleados = obtenerEmpleadosVinculadosAlDirector();
        if (empleados.isEmpty()) {
            return Response.ok(new ArrayList<>()).build();
        }

        sincronizarResguardosHuerfanosParaDirector(empleados);

        List<Long> idsEmpleados = new ArrayList<>();
        for (Empleado e : empleados) {
            if (e != null && e.idEmpleado != null) {
                idsEmpleados.add(e.idEmpleado);
            }
        }

        List<Resguardo> pendientes = Resguardo.find(
                "empleado.idEmpleado in ?1 and (( (firmaEmpleadoBase64 is null or firmaEmpleadoBase64 = '') and (estadoResguardo is null or estadoResguardo = 'ACTIVO' or estadoResguardo = 'PENDIENTE_FIRMA') ) or estadoResguardo = 'BAJA_EN_PROCESO')",
                idsEmpleados
        ).list();
        List<PendienteDTO> lista = new ArrayList<>();
        
        for (Resguardo r : pendientes) {
            if (r == null) continue;
            PendienteDTO dto = new PendienteDTO();
            dto.idResguardo = r.id;
            dto.folioResguardoOficial = r.folioResguardoOficial;
            dto.fondoOrigen = r.fondoOrigen;
            if (r.articulo != null) {
                dto.equipoMarca = r.articulo.marca;
                dto.equipoSerie = r.articulo.numeroSerie;
                dto.numeroInventario = r.articulo.numeroInventario;
                if (dto.folioResguardoOficial == null || dto.folioResguardoOficial.isBlank()) {
                    dto.folioResguardoOficial = r.articulo.folioResguardo;
                }
                if (dto.fondoOrigen == null || dto.fondoOrigen.isBlank()) {
                    dto.fondoOrigen = r.articulo.fondoOrigen;
                }
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
            poblarDatosArticuloDTO(dto, r);
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

        List<Empleado> empleadosVinculados = obtenerEmpleadosVinculadosAlDirector();
        if (empleadosVinculados.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
        Empleado empleado = empleadosVinculados.get(0);

        Resguardo resguardo = Resguardo.findById(idResguardo);
        if (resguardo == null) return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"Resguardo no encontrado\"}").build();

        boolean autorizado = false;
        if (resguardo.empleado != null) {
            for (Empleado e : empleadosVinculados) {
                if (e.idEmpleado != null && e.idEmpleado.equals(resguardo.empleado.idEmpleado)) {
                    autorizado = true;
                    break;
                }
                if (e.cct != null && !e.cct.isBlank() && resguardo.empleado.cct != null
                        && e.cct.trim().equalsIgnoreCase(resguardo.empleado.cct.trim())) {
                    autorizado = true;
                    break;
                }
            }
        }
        if (!autorizado && resguardo.articulo != null && resguardo.articulo.centroTrabajo != null) {
            for (Empleado e : empleadosVinculados) {
                if (e.cct != null && e.cct.trim().equalsIgnoreCase(resguardo.articulo.centroTrabajo.trim())) {
                    autorizado = true;
                    break;
                }
            }
        }

        if (!autorizado) {
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

        resguardo.empleado = empleado;
        resguardo.firmaEmpleadoBase64 = request.firmaBase64;
        resguardo.latitudGPS = request.latitudGPS.trim();
        resguardo.longitudGPS = request.longitudGPS.trim();
        resguardo.estadoResguardo = "ACTIVO";
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = resguardo.articulo;
        historial.tipoMovimiento = "FIRMA_RECEPCION";
        historial.detallesModificacion = "El Director " + empleado.nombre + " firmó de conformidad la recepción institucional del equipo (Folio Resguardo: "
                + (resguardo.folioResguardoOficial != null ? resguardo.folioResguardoOficial : "S/F") + ").";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = empleado.usuario != null ? empleado.usuario : jwt.getName(); 
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

        List<Empleado> empleadosVinculados = obtenerEmpleadosVinculadosAlDirector();
        if (!empleadosVinculados.isEmpty()) {
            Empleado principal = empleadosVinculados.get(0);
            boolean pertenece = empleadosVinculados.stream().anyMatch(e ->
                    (resguardo.empleado != null && e.idEmpleado != null && e.idEmpleado.equals(resguardo.empleado.idEmpleado)) ||
                    (resguardo.empleado != null && e.cct != null && resguardo.empleado.cct != null && e.cct.trim().equalsIgnoreCase(resguardo.empleado.cct.trim())) ||
                    (resguardo.articulo != null && e.cct != null && resguardo.articulo.centroTrabajo != null && e.cct.trim().equalsIgnoreCase(resguardo.articulo.centroTrabajo.trim()))
            );
            if (pertenece) {
                resguardo.empleado = principal;
            }
        }

        resguardo.estadoResguardo = "SOLICITUD_BAJA";
        resguardo.persist();

        HistorialTrazabilidad historial = new HistorialTrazabilidad();
        historial.articulo = resguardo.articulo;
        historial.tipoMovimiento = "SOLICITUD_BAJA";
        historial.detallesModificacion = "La escuela solicitó la baja/devolución del equipo.";
        historial.fechaMovimiento = java.time.LocalDateTime.now();
        historial.username = jwt != null ? jwt.getName() : "Director"; 
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
        String folioActa = (resguardo.folioResguardoOficial != null && !resguardo.folioResguardoOficial.isBlank())
                ? resguardo.folioResguardoOficial
                : String.format("%05d", consecutivoActa);

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
            return Response.ok(baos.toByteArray()).header("Content-Disposition", "attachment; filename=\"Acta_Asignacion_" + folioActa.replace(" ", "_") + ".pdf\"").build();

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
