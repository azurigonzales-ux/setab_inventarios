package org.acme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/traspasos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TraspasoResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    PostgresRelacionalCrudService relacionalService;

    @Inject
    UsuarioResource usuarioResource;

    private String obtenerNombreAdmin() {
        String adminNombre = "Administrador del Sistema";
        if (jwt != null && jwt.getName() != null) {
            Usuario adminUser = Usuario.find("upper(username) = ?1", jwt.getName().trim().toUpperCase()).firstResult();
            if (adminUser != null && adminUser.nombreCompleto != null && !adminUser.nombreCompleto.isEmpty()) {
                adminNombre = adminUser.nombreCompleto;
            } else {
                adminNombre = jwt.getName();
            }
        }
        return adminNombre;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TraspasoRequest {
        public Long idEmpleadoSaliente;
        public Long idEmpleadoEntrante;
        public String cct;
        public String firmaSalienteBase64;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ActaTraspasoDTO {
        public String cct;
        public String nombreEscuela;
        public String directorSaliente;
        public String directorEntrante;
        public String rfcEntrante;
        public String fechaTraspaso;
        public String elaboradoPor;
        public String firmaSalienteBase64;
        public List<Resguardo> bienesTraspasados;
    }

    @POST
    @Transactional
    @RolesAllowed("SUPERADMIN")
    public Response ejecutarTraspaso(TraspasoRequest request) {
        try {
            if (request == null || request.idEmpleadoEntrante == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Debe seleccionar al Director Entrante.\"}").build();
            }

            Empleado directorEntrante = relacionalService != null
                    ? relacionalService.sincronizarEmpleadoEnPanache(request.idEmpleadoEntrante)
                    : Empleado.findById(request.idEmpleadoEntrante);
            if (directorEntrante == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Director entrante no encontrado.\"}").build();
            }

            Empleado directorSaliente = request.idEmpleadoSaliente != null
                    ? (relacionalService != null
                            ? relacionalService.sincronizarEmpleadoEnPanache(request.idEmpleadoSaliente)
                            : Empleado.findById(request.idEmpleadoSaliente))
                    : null;
            String cctLimpia = (request.cct != null && !request.cct.trim().isEmpty())
                    ? request.cct.trim().toUpperCase()
                    : (directorSaliente != null && directorSaliente.cct != null ? directorSaliente.cct.trim().toUpperCase() : "SIN-CCT");

            List<Resguardo> resguardosActivos = new ArrayList<>();
            if (directorSaliente != null) {
                List<Long> idsSaliente = new ArrayList<>();
                if (directorSaliente.idEmpleado != null) idsSaliente.add(directorSaliente.idEmpleado);
                if (request.idEmpleadoSaliente != null && !idsSaliente.contains(request.idEmpleadoSaliente)) {
                    idsSaliente.add(request.idEmpleadoSaliente);
                }
                if (!idsSaliente.isEmpty()) {
                    resguardosActivos.addAll(Resguardo.find(
                            "empleado.idEmpleado in ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA')",
                            idsSaliente
                    ).list());
                }
            }
            if (resguardosActivos.isEmpty() && !"SIN-CCT".equals(cctLimpia)) {
                resguardosActivos.addAll(Resguardo.find(
                        "upper(empleado.cct) = ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA')",
                        cctLimpia
                ).list());
            }

            if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
                List<Articulo> bienesRel = relacionalService.listarBienesPorResponsableOCct(
                        request.idEmpleadoSaliente,
                        directorSaliente != null ? directorSaliente.rfc : null,
                        cctLimpia,
                        directorSaliente != null ? directorSaliente.nombre : null
                );
                for (Articulo ext : bienesRel) {
                    if (ext == null) continue;
                    Articulo artLocal = relacionalService.sincronizarObjetoArticuloEnPanache(ext, null);
                    if (artLocal == null || artLocal.id == null) continue;
                    boolean yaExiste = resguardosActivos.stream()
                            .anyMatch(r -> r != null && r.articulo != null && artLocal.id.equals(r.articulo.id));
                    if (!yaExiste) {
                        Resguardo rSync = new Resguardo();
                        rSync.articulo = artLocal;
                        rSync.empleado = directorSaliente;
                        rSync.fechaAsignacion = artLocal.fechaResguardo != null ? artLocal.fechaResguardo : LocalDate.now();
                        rSync.cantidadAsignada = 1.0;
                        rSync.estadoResguardo = "ACTIVO";
                        rSync.nombreAdminAsignador = obtenerNombreAdmin();
                        rSync.fondoOrigen = artLocal.fondoOrigen != null ? artLocal.fondoOrigen : "OFICIAL";
                        rSync.folioResguardoOficial = artLocal.folioResguardo;
                        rSync.persistAndFlush();
                        resguardosActivos.add(rSync);
                    }
                }
            }

            if (resguardosActivos.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"El director o escuela seleccionada no tiene equipos activos bajo resguardo para traspasar.\"}").build();
            }

            if (directorSaliente == null) {
                directorSaliente = resguardosActivos.get(0).empleado;
            }

            String directorSalienteNombre = directorSaliente != null ? directorSaliente.nombre : "Director Saliente";
            String nombreEscuelaActual = (directorSaliente != null && directorSaliente.nombreEscuela != null)
                    ? directorSaliente.nombreEscuela
                    : "Centro Educativo SETAB";

            directorEntrante.cct = cctLimpia;
            directorEntrante.nombreEscuela = nombreEscuelaActual;
            directorEntrante.persist();

            CentroTrabajo ctEntity = CentroTrabajo.find("upper(claveCT) = ?1", cctLimpia).firstResult();
            if (ctEntity != null) {
                ctEntity.director = directorEntrante.nombre;
                ctEntity.persist();
            }

            if (directorEntrante.usuario != null && !directorEntrante.usuario.trim().isEmpty()) {
                Usuario userEntrante = Usuario.find("upper(username) = ?1", directorEntrante.usuario.trim().toUpperCase()).firstResult();
                if (userEntrante != null) {
                    userEntrante.cct = cctLimpia;
                    userEntrante.nombreEscuela = nombreEscuelaActual;
                    userEntrante.persist();
                }
            }

            if (usuarioResource != null) {
                try {
                    usuarioResource.sincronizarDirectorConKeycloak(directorEntrante, null, null);
                } catch (Exception ignored) {
                }
            }

            String anioActual = String.valueOf(LocalDate.now().getYear());
            Map<String, String> foliosCrePorFondo = new HashMap<>();
            String adminResponsable = obtenerNombreAdmin();
            String rfcOClaveEntrante = (directorEntrante.rfc != null && !directorEntrante.rfc.isBlank())
                    ? directorEntrante.rfc
                    : (directorEntrante.claveResponsable != null && !directorEntrante.claveResponsable.isBlank()
                            ? directorEntrante.claveResponsable
                            : directorEntrante.nombre);

            for (Resguardo rAntiguo : resguardosActivos) {
                String fondo = "INTERNO";
                if (rAntiguo.articulo != null && rAntiguo.articulo.fondoOrigen != null && !rAntiguo.articulo.fondoOrigen.isBlank()) {
                    fondo = rAntiguo.articulo.fondoOrigen.trim().toUpperCase();
                } else if (rAntiguo.fondoOrigen != null && !rAntiguo.fondoOrigen.isBlank()) {
                    fondo = rAntiguo.fondoOrigen.trim().toUpperCase();
                }

                String folioAnterior = (rAntiguo.folioResguardoOficial != null && !rAntiguo.folioResguardoOficial.isBlank())
                        ? rAntiguo.folioResguardoOficial
                        : (rAntiguo.articulo != null && rAntiguo.articulo.folioResguardo != null ? rAntiguo.articulo.folioResguardo : "S/F");

                String nuevoFolioCre = foliosCrePorFondo.get(fondo);
                if (nuevoFolioCre == null) {
                    long consecutivoFondo = Resguardo.count("upper(fondoOrigen) = ?1", fondo) + 1;
                    nuevoFolioCre = String.format("%s %05d-CRE", anioActual, consecutivoFondo);
                    foliosCrePorFondo.put(fondo, nuevoFolioCre);
                }

                rAntiguo.estadoResguardo = "TRASPASADO";
                rAntiguo.fechaBaja = LocalDate.now();
                rAntiguo.firmaDirectorBajaBase64 = request.firmaSalienteBase64;
                rAntiguo.persist();

                if (rAntiguo.articulo != null) {
                    rAntiguo.articulo.resguardanteHistorico = directorSalienteNombre;
                    rAntiguo.articulo.resguardanteActual = directorEntrante.nombre;
                    rAntiguo.articulo.centroTrabajo = cctLimpia;
                    rAntiguo.articulo.fechaResguardo = LocalDate.now();
                    rAntiguo.articulo.folioResguardo = nuevoFolioCre;
                    rAntiguo.articulo.persist();
                }

                Resguardo rNuevo = new Resguardo();
                rNuevo.articulo = rAntiguo.articulo;
                rNuevo.empleado = directorEntrante;
                rNuevo.fechaAsignacion = LocalDate.now();
                rNuevo.cantidadAsignada = rAntiguo.cantidadAsignada;
                rNuevo.estadoResguardo = "PENDIENTE_FIRMA";
                rNuevo.nombreAdminAsignador = adminResponsable;
                rNuevo.firmaAdminBase64 = request.firmaSalienteBase64;
                rNuevo.firmaEmpleadoBase64 = null;
                rNuevo.fondoOrigen = fondo;
                rNuevo.folioResguardoOficial = nuevoFolioCre;
                rNuevo.persist();

                String numInventario = (rAntiguo.articulo != null && rAntiguo.articulo.numeroInventario != null)
                        ? rAntiguo.articulo.numeroInventario
                        : (rAntiguo.articulo != null ? String.valueOf(rAntiguo.articulo.id) : "S/N");

                if (relacionalService != null && relacionalService.puedeOperarInventario(null)) {
                    relacionalService.actualizarResguardoEnInventarioRelacional(
                            numInventario,
                            null,
                            cctLimpia,
                            request.idEmpleadoEntrante,
                            rfcOClaveEntrante,
                            nuevoFolioCre,
                            "CRE"
                    );
                }

                HistorialTrazabilidad hBajaRotacion = new HistorialTrazabilidad();
                hBajaRotacion.articulo = rAntiguo.articulo;
                hBajaRotacion.tipoMovimiento = "BAJA_RESGUARDO_ROTACION";
                hBajaRotacion.detallesModificacion = "Baja de Resguardo por Rotación en CCT " + cctLimpia
                        + " | No. Inventario: " + numInventario
                        + " | Folio Resguardo Anterior: " + folioAnterior
                        + " | Director Saliente: " + directorSalienteNombre;
                hBajaRotacion.fechaMovimiento = LocalDateTime.now();
                hBajaRotacion.username = adminResponsable;
                hBajaRotacion.persist();

                HistorialTrazabilidad hAltaRotacion = new HistorialTrazabilidad();
                hAltaRotacion.articulo = rAntiguo.articulo;
                hAltaRotacion.tipoMovimiento = "ALTA_RESGUARDO_ROTACION";
                hAltaRotacion.detallesModificacion = "Alta de Resguardo por Rotación [" + fondo + "] en CCT " + cctLimpia
                        + " | No. Inventario: " + numInventario
                        + " | Nuevo Folio Resguardo: " + nuevoFolioCre
                        + " | Director Entrante: " + directorEntrante.nombre;
                hAltaRotacion.fechaMovimiento = LocalDateTime.now();
                hAltaRotacion.username = adminResponsable;
                hAltaRotacion.persist();
            }

            return Response.ok("{\"mensaje\": \"Traspaso ejecutado correctamente\", \"cct\": \"" + cctLimpia + "\"}").build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Error interno al ejecutar el traspaso";
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + msg + "\"}").build();
        }
    }

    @GET
    @Path("/acta/{idEmpleadoEntrante}")
    @Transactional
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response obtenerDatosActaTraspaso(@PathParam("idEmpleadoEntrante") Long idEmpleadoEntrante) {
        Empleado entrante = relacionalService != null
                ? relacionalService.sincronizarEmpleadoEnPanache(idEmpleadoEntrante)
                : Empleado.findById(idEmpleadoEntrante);
        if (entrante == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<Long> idsEntrante = new ArrayList<>();
        if (entrante.idEmpleado != null) idsEntrante.add(entrante.idEmpleado);
        if (idEmpleadoEntrante != null && !idsEntrante.contains(idEmpleadoEntrante)) idsEntrante.add(idEmpleadoEntrante);

        String cctLimpia = entrante.cct != null ? entrante.cct.trim().toUpperCase() : "S/N";
        List<Resguardo> vigentes = Resguardo.find(
                "empleado.idEmpleado in ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo = 'PENDIENTE_FIRMA')",
                idsEntrante
        ).list();
        List<Resguardo> traspasados = Resguardo.find("upper(empleado.cct) = ?1 and estadoResguardo = 'TRASPASADO' order by id desc", cctLimpia).list();

        ActaTraspasoDTO acta = new ActaTraspasoDTO();
        acta.cct = cctLimpia;
        acta.nombreEscuela = entrante.nombreEscuela;
        acta.directorEntrante = entrante.nombre;
        acta.rfcEntrante = entrante.rfc;
        acta.fechaTraspaso = LocalDate.now().toString();
        acta.elaboradoPor = obtenerNombreAdmin();
        acta.bienesTraspasados = vigentes;

        if (!traspasados.isEmpty() && traspasados.get(0).empleado != null) {
            acta.directorSaliente = traspasados.get(0).empleado.nombre;
            acta.firmaSalienteBase64 = traspasados.get(0).firmaDirectorBajaBase64;
        } else {
            acta.directorSaliente = "RESPONSABLE ANTERIOR";
        }

        return Response.ok(acta).build();
    }
}
