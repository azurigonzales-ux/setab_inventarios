package org.acme;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

            Empleado directorEntrante = Empleado.findById(request.idEmpleadoEntrante);
            if (directorEntrante == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\": \"Director entrante no encontrado.\"}").build();
            }

            Empleado directorSaliente = request.idEmpleadoSaliente != null ? Empleado.findById(request.idEmpleadoSaliente) : null;
            String cctLimpia = (request.cct != null && !request.cct.trim().isEmpty())
                    ? request.cct.trim().toUpperCase()
                    : (directorSaliente != null && directorSaliente.cct != null ? directorSaliente.cct.trim().toUpperCase() : "SIN-CCT");

            List<Resguardo> resguardosActivos;
            if (directorSaliente != null) {
                resguardosActivos = Resguardo.find("empleado.idEmpleado = ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA')", directorSaliente.idEmpleado).list();
            } else {
                resguardosActivos = Resguardo.find("upper(empleado.cct) = ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo is null or estadoResguardo = 'PENDIENTE_FIRMA')", cctLimpia).list();
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

            for (Resguardo rAntiguo : resguardosActivos) {
                rAntiguo.estadoResguardo = "TRASPASADO";
                rAntiguo.fechaBaja = LocalDate.now();
                rAntiguo.firmaDirectorBajaBase64 = request.firmaSalienteBase64;
                rAntiguo.persist();

                Resguardo rNuevo = new Resguardo();
                rNuevo.articulo = rAntiguo.articulo;
                rNuevo.empleado = directorEntrante;
                rNuevo.fechaAsignacion = LocalDate.now();
                rNuevo.cantidadAsignada = rAntiguo.cantidadAsignada;
                rNuevo.estadoResguardo = "PENDIENTE_FIRMA";
                rNuevo.nombreAdminAsignador = obtenerNombreAdmin();
                rNuevo.firmaAdminBase64 = request.firmaSalienteBase64;
                rNuevo.firmaEmpleadoBase64 = null;
                rNuevo.persist();

                HistorialTrazabilidad historial = new HistorialTrazabilidad();
                historial.articulo = rAntiguo.articulo;
                historial.tipoMovimiento = "ROTACION_DIRECTOR";
                historial.detallesModificacion = "Traspaso en CCT " + cctLimpia + ". Entrega: " + directorSalienteNombre + " | Recibe: " + directorEntrante.nombre;
                historial.fechaMovimiento = LocalDateTime.now();
                historial.username = obtenerNombreAdmin();
                historial.persist();
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
    @RolesAllowed({"SUPERADMIN", "admin", "Admin"})
    public Response obtenerDatosActaTraspaso(@PathParam("idEmpleadoEntrante") Long idEmpleadoEntrante) {
        Empleado entrante = Empleado.findById(idEmpleadoEntrante);
        if (entrante == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String cctLimpia = entrante.cct != null ? entrante.cct.trim().toUpperCase() : "S/N";
        List<Resguardo> vigentes = Resguardo.find("empleado.idEmpleado = ?1 and (estadoResguardo = 'ACTIVO' or estadoResguardo = 'PENDIENTE_FIRMA')", idEmpleadoEntrante).list();
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