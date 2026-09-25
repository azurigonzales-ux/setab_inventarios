package org.acme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

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

    @ConfigProperty(name = "keycloak.admin.server-url")
    String keycloakServerUrl;

    @ConfigProperty(name = "keycloak.admin.realm")
    String keycloakRealm;

    @ConfigProperty(name = "keycloak.admin.client-id")
    String keycloakClientId;

    @ConfigProperty(name = "keycloak.admin.username")
    String keycloakUsername;

    @ConfigProperty(name = "keycloak.admin.password")
    String keycloakPassword;

    public static class UsuarioDTO {
        public Long id;
        public String nombreCompleto;
        public String username;
        public String rol;
        public String estado;
        public String cct;
        public String nivelEducativo;
    }

    // AÑADIDOS APELLIDOS Y EMAIL PARA SATISFACER A KEYCLOAK
    public static class NuevoUsuarioRequest {
        public String nombre;
        public String apellidos;
        public String email;
        public String username;
        public String password;
        public String rol;
        public String cct;
        public String nivelEducativo;
        public String nombreEscuela; 
    }

    public static class PasswordRequest {
        public String nuevaPassword;
    }

    private Keycloak getAdminKeycloakClient() {
        return KeycloakBuilder.builder()
                .serverUrl(keycloakServerUrl)
                .realm(keycloakRealm)
                .clientId(keycloakClientId)
                .username(keycloakUsername)
                .password(keycloakPassword)
                .build();
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
                           .entity("{\"mensaje\": \"El nombre de usuario ya existe en el sistema local\"}").build();
        }

        Keycloak keycloak = null;
        String rolExactoKeycloak = "Admin"; 

        try {
            keycloak = getAdminKeycloakClient();
            RealmResource realmResource = keycloak.realm("setab-erp");

            UserRepresentation user = new UserRepresentation();
            user.setUsername(request.username);
            
            // INYECCIÓN DE DATOS OBLIGATORIOS DE KEYCLOAK
            user.setFirstName(request.nombre);
            user.setLastName(request.apellidos);
            user.setEmail(request.email);
            user.setEmailVerified(true); // Evita la pantalla de "Update Account"
            user.setEnabled(true);

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(request.password);
            credential.setTemporary(false);
            user.setCredentials(Collections.singletonList(credential));

            jakarta.ws.rs.core.Response kcResponse = realmResource.users().create(user);
            
            if (kcResponse.getStatus() != 201) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"mensaje\": \"El usuario ya existe en Keycloak o faltan datos.\"}")
                        .build();
            }

            String userId = kcResponse.getLocation().getPath().replaceAll(".*/([^/]+)$", "$1");
            
            if (request.rol != null && request.rol.equalsIgnoreCase("SUPERADMIN")) {
                rolExactoKeycloak = "SUPERADMIN"; 
            }

            try {
                RoleRepresentation realmRole = realmResource.roles().get(rolExactoKeycloak).toRepresentation();
                realmResource.users().get(userId).roles().realmLevel().add(Collections.singletonList(realmRole));
            } catch (Exception rolEx) {
                System.err.println("CRÍTICO: No se pudo asignar el rol '" + rolExactoKeycloak + "'. Asegúrate de que exista exactamente con ese nombre.");
                rolEx.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace(); 
            return Response.serverError().entity("{\"mensaje\": \"Fallo la sincronización con Keycloak: " + e.getMessage() + "\"}").build();
        } finally {
            if (keycloak != null) {
                keycloak.close();
            }
        }

        // Unimos el nombre para la BD de PostgreSQL
        String nombreCompletoArmado = request.nombre + " " + request.apellidos;

        Usuario nuevo = new Usuario();
        nuevo.nombreCompleto = nombreCompletoArmado;
        nuevo.username = request.username;
        nuevo.password = request.password; 
        nuevo.rol = rolExactoKeycloak; 
        nuevo.estado = "Activo";
        nuevo.cct = request.cct;
        nuevo.nivelEducativo = request.nivelEducativo;
        nuevo.persist();

        Empleado nuevoEmpleado = new Empleado();
        nuevoEmpleado.nombre = nombreCompletoArmado;
        nuevoEmpleado.rfc = "S/N"; 
        nuevoEmpleado.cct = request.cct;
        nuevoEmpleado.usuario = request.username;
        nuevoEmpleado.nombreEscuela = request.nombreEscuela; 
        nuevoEmpleado.cargo = "Director";
        nuevoEmpleado.area = (request.nivelEducativo != null) ? request.nivelEducativo : "Educación Básica";
        nuevoEmpleado.persist();

        return Response.status(Response.Status.CREATED).entity("{\"mensaje\": \"Usuario sincronizado exitosamente\"}").build();
    }

    // ... (Mantén tus métodos resetearPassword y cambiarEstado exactamente igual) ...

    @PUT
    @Path("/{id}/reset-password")
    @Transactional
    public Response resetearPassword(@PathParam("id") Long id, PasswordRequest request) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) return Response.status(Response.Status.NOT_FOUND).build();

        Keycloak keycloak = null;
        try {
            keycloak = getAdminKeycloakClient();
            RealmResource realmResource = keycloak.realm("setab-erp");
            List<UserRepresentation> users = realmResource.users().search(usuario.username);
            
            if (!users.isEmpty()) {
                String keycloakUserId = users.get(0).getId();
                CredentialRepresentation credential = new CredentialRepresentation();
                credential.setType(CredentialRepresentation.PASSWORD);
                credential.setValue(request.nuevaPassword);
                credential.setTemporary(false);
                realmResource.users().get(keycloakUserId).resetPassword(credential);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Response.serverError().entity("{\"mensaje\": \"Error en Keycloak.\"}").build();
        } finally {
            if (keycloak != null) keycloak.close();
        }
        usuario.password = request.nuevaPassword;
        usuario.persist();
        return Response.ok("{\"mensaje\": \"Clave actualizada\"}").build();
    }

    @PUT
    @Path("/{id}/toggle-estado")
    @Transactional
    public Response cambiarEstado(@PathParam("id") Long id) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) return Response.status(Response.Status.NOT_FOUND).build();
        
        boolean habilitar = false;
        if ("Activo".equalsIgnoreCase(usuario.estado)) {
            usuario.estado = "Inactivo"; habilitar = false;
        } else {
            usuario.estado = "Activo"; habilitar = true;
        }

        Keycloak keycloak = null;
        try {
            keycloak = getAdminKeycloakClient();
            RealmResource realmResource = keycloak.realm("setab-erp");
            List<UserRepresentation> users = realmResource.users().search(usuario.username);
            if (!users.isEmpty()) {
                UserRepresentation userKc = users.get(0);
                userKc.setEnabled(habilitar);
                realmResource.users().get(userKc.getId()).update(userKc);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (keycloak != null) keycloak.close();
        }
        usuario.persist();
        return Response.ok("{\"mensaje\": \"Estado actualizado a " + usuario.estado + "\"}").build();
    }
}