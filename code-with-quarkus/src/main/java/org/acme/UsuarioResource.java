package org.acme;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
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

@Path("/api/usuarios")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SUPERADMIN"})
public class UsuarioResource {

    public static class UsuarioDTO {
        public Long id;
        public String nombreCompleto;
        public String username;
        public String rol;
        public String estado;
        public String cct;
        public String nivelEducativo;
    }

    public static class NuevoUsuarioRequest {
        public String nombreCompleto;
        public String username;
        public String password;
        public String rol;
        public String cct;
        public String nivelEducativo;
        public String nombreEscuela; // ¡Nuevo campo atrapado desde el frontend!
    }

    public static class PasswordRequest {
        public String nuevaPassword;
    }

    @GET
    public Response listarUsuarios() {
        List<Usuario> usuarios = Usuario.listAll();
        List<UsuarioDTO> lista = new ArrayList<>();
        
        for (Usuario u : usuarios) {
            UsuarioDTO dto = new UsuarioDTO();
            dto.id = u.id;
            dto.nombreCompleto = u.nombreCompleto;
            dto.username = u.username;
            dto.rol = u.rol;
            dto.estado = u.estado;
            dto.cct = u.cct;
            dto.nivelEducativo = u.nivelEducativo;
            lista.add(dto);
        }
        return Response.ok(lista).build();
    }

    @POST
    @Transactional
    public Response crearUsuario(NuevoUsuarioRequest request) {
        if (Usuario.count("username", request.username) > 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity("{\"mensaje\": \"El nombre de usuario ya existe\"}").build();
        }

        Usuario nuevo = new Usuario();
        nuevo.nombreCompleto = request.nombreCompleto;
        nuevo.username = request.username;
        nuevo.password = request.password;
        nuevo.rol = (request.rol != null && !request.rol.trim().isEmpty()) ? request.rol : "Usuario";
        nuevo.estado = "Activo";
        nuevo.cct = request.cct;
        nuevo.nivelEducativo = request.nivelEducativo;
        nuevo.persist();

        // --- SINCRONIZACIÓN AUTOMÁTICA COMPLETA ---
        Empleado nuevoEmpleado = new Empleado();
        nuevoEmpleado.nombre = request.nombreCompleto;
        nuevoEmpleado.rfc = "S/N"; 
        nuevoEmpleado.cct = request.cct;
        nuevoEmpleado.usuario = request.username;
        
        // Guardamos el nombre real de la escuela que escribiste
        nuevoEmpleado.nombreEscuela = request.nombreEscuela; 
        
        nuevoEmpleado.cargo = "Director";
        nuevoEmpleado.area = (request.nivelEducativo != null) ? request.nivelEducativo : "Educación Básica";
        
        nuevoEmpleado.persist();

        return Response.status(Response.Status.CREATED).entity("{\"mensaje\": \"Usuario y Empleado creados exitosamente\"}").build();
    }

    @PUT
    @Path("/{id}/reset-password")
    @Transactional
    public Response resetearPassword(@PathParam("id") Long id, PasswordRequest request) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        usuario.password = request.nuevaPassword;
        usuario.persist();
        
        return Response.ok("{\"mensaje\": \"Contraseña actualizada correctamente\"}").build();
    }

    @PUT
    @Path("/{id}/toggle-estado")
    @Transactional
    public Response cambiarEstado(@PathParam("id") Long id) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if ("Activo".equalsIgnoreCase(usuario.estado)) {
            usuario.estado = "Inactivo";
        } else {
            usuario.estado = "Activo";
        }
        
        usuario.persist();
        
        return Response.ok("{\"mensaje\": \"Estado del usuario actualizado a " + usuario.estado + "\"}").build();
    }
}