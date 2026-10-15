package org.acme;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Path("/api/usuarios")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("SUPERADMIN")
public class UsuarioResource {

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    Optional<String> oidcAuthServerUrl;

    @ConfigProperty(name = "keycloak.admin.url")
    Optional<String> customKeycloakUrl;

    @ConfigProperty(name = "keycloak.admin.realm")
    Optional<String> customTargetRealm;

    @ConfigProperty(name = "keycloak.admin.master-realm", defaultValue = "master")
    String adminRealm;

    @ConfigProperty(name = "keycloak.admin.client-id", defaultValue = "admin-cli")
    String adminClientId;

    @ConfigProperty(name = "keycloak.admin.username", defaultValue = "admin")
    String adminUser;

    @ConfigProperty(name = "keycloak.admin.password", defaultValue = "admin")
    String adminPassword;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public static class NuevoUsuarioDTO {
        public String nombre;
        public String apellidos;
        public String email;
        public String username;
        public String password;
        public String cct;
        public String nivelEducativo;
        public String nombreEscuela;
        public String rol;
    }

    public static class PasswordDTO {
        public String nuevaPassword;
    }

    private String resolverKeycloakBaseUrl() {
        if (customKeycloakUrl.isPresent() && !customKeycloakUrl.get().isBlank()) {
            return customKeycloakUrl.get().trim();
        }
        if (oidcAuthServerUrl.isPresent() && oidcAuthServerUrl.get().contains("/realms/")) {
            String url = oidcAuthServerUrl.get().trim();
            return url.substring(0, url.indexOf("/realms/"));
        }
        return "http://localhost:8080";
    }

    private String resolverTargetRealm() {
        if (customTargetRealm.isPresent() && !customTargetRealm.get().isBlank()) {
            return customTargetRealm.get().trim();
        }
        if (oidcAuthServerUrl.isPresent() && oidcAuthServerUrl.get().contains("/realms/")) {
            String url = oidcAuthServerUrl.get().trim();
            String resto = url.substring(url.indexOf("/realms/") + 8);
            int slash = resto.indexOf('/');
            return slash > 0 ? resto.substring(0, slash) : resto;
        }
        return "setab-erp";
    }

    @GET
    @Transactional
    public Response listarUsuarios() {
        List<Usuario> usuarios = Usuario.listAll();
        if (usuarios.isEmpty()) {
            List<Empleado> empleados = Empleado.listAll();
            for (Empleado emp : empleados) {
                if (emp.usuario != null && !emp.usuario.trim().isEmpty()) {
                    Usuario u = new Usuario();
                    u.username = emp.usuario.trim().toUpperCase();
                    u.nombreCompleto = emp.nombre;
                    u.cct = emp.cct;
                    u.nivelEducativo = emp.area != null ? emp.area : "Primaria";
                    u.nombreEscuela = emp.nombreEscuela;
                    u.rol = (emp.cargo != null && emp.cargo.toUpperCase().contains("ALMAC")) ? "SUPERADMIN" : "Admin";
                    u.estado = "Activo";
                    u.idEmpleado = emp.idEmpleado;
                    u.persist();
                }
            }
            usuarios = Usuario.listAll();
        }
        return Response.ok(usuarios).build();
    }

    @POST
    @Transactional
    public Response crearUsuario(NuevoUsuarioDTO dto) {
        if (dto == null || dto.username == null || dto.username.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"El nombre de usuario es obligatorio.\"}").build();
        }

        String cleanUsername = dto.username.trim().toUpperCase();
        Usuario existente = Usuario.find("upper(username) = ?1", cleanUsername).firstResult();
        if (existente != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"El usuario o RFC ya está registrado.\"}").build();
        }

        String nombreCompleto = ((dto.nombre != null ? dto.nombre.trim() : "") + " " + (dto.apellidos != null ? dto.apellidos.trim() : "")).trim().toUpperCase();
        String rolNormalizado = (dto.rol != null && dto.rol.equalsIgnoreCase("SUPERADMIN")) ? "SUPERADMIN" : "Admin";
        String rolKeycloak = rolNormalizado.equals("SUPERADMIN") ? "SUPERADMIN" : "admin";

        String keycloakUserId = sincronizarCreacionKeycloak(
                cleanUsername,
                dto.nombre != null ? dto.nombre.trim().toUpperCase() : cleanUsername,
                dto.apellidos != null ? dto.apellidos.trim().toUpperCase() : "",
                dto.email != null ? dto.email.trim() : cleanUsername.toLowerCase() + "@setab.gob.mx",
                dto.password != null ? dto.password : "Setab2026*",
                rolKeycloak
        );

        Empleado emp = Empleado.find("upper(usuario) = ?1", cleanUsername).firstResult();
        if (emp == null) {
            emp = new Empleado();
        }
        emp.nombre = nombreCompleto;
        emp.rfc = cleanUsername;
        emp.usuario = cleanUsername;
        emp.cct = dto.cct != null ? dto.cct.trim().toUpperCase() : "CENTRAL";
        emp.nombreEscuela = dto.nombreEscuela != null ? dto.nombreEscuela.trim().toUpperCase() : "ALMACÉN CENTRAL";
        emp.area = dto.nivelEducativo != null ? dto.nivelEducativo : "Área Central";
        emp.cargo = rolNormalizado.equals("SUPERADMIN") ? "Administrador de Almacén" : "Director de Escuela";
        emp.persist();

        Usuario nuevo = new Usuario();
        nuevo.username = cleanUsername;
        nuevo.password = dto.password;
        nuevo.nombreCompleto = nombreCompleto;
        nuevo.email = dto.email;
        nuevo.cct = emp.cct;
        nuevo.nivelEducativo = emp.area;
        nuevo.nombreEscuela = emp.nombreEscuela;
        nuevo.rol = rolNormalizado;
        nuevo.estado = "Activo";
        nuevo.keycloakId = keycloakUserId;
        nuevo.idEmpleado = emp.idEmpleado;
        nuevo.persist();

        return Response.status(Response.Status.CREATED).entity(nuevo).build();
    }

    @PUT
    @Path("/{id}/reset-password")
    @Transactional
    public Response resetPassword(@PathParam("id") Long id, PasswordDTO dto) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        usuario.password = dto.nuevaPassword;
        usuario.persist();

        actualizarPasswordKeycloak(usuario.username, usuario.keycloakId, dto.nuevaPassword);

        return Response.ok("{\"mensaje\": \"Contraseña actualizada correctamente\"}").build();
    }

    @PUT
    @Path("/{id}/toggle-estado")
    @Transactional
    public Response toggleEstado(@PathParam("id") Long id) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        boolean nuevoEstadoActivo = !"Activo".equalsIgnoreCase(usuario.estado);
        usuario.estado = nuevoEstadoActivo ? "Activo" : "Inactivo";
        usuario.persist();

        actualizarEstadoKeycloak(usuario.username, usuario.keycloakId, nuevoEstadoActivo);

        return Response.ok(usuario).build();
    }

    private String obtenerAdminTokenKeycloak() {
        String baseUrl = resolverKeycloakBaseUrl();
        try {
            String form = "client_id=" + URLEncoder.encode(adminClientId, StandardCharsets.UTF_8)
                    + "&username=" + URLEncoder.encode(adminUser, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(adminPassword, StandardCharsets.UTF_8)
                    + "&grant_type=password";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/realms/" + adminRealm + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode node = mapper.readTree(resp.body());
                if (node.has("access_token")) {
                    return node.get("access_token").asText();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private String buscarKeycloakUserId(String token, String username) {
        String baseUrl = resolverKeycloakBaseUrl();
        String realm = resolverTargetRealm();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&exact=true"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode arr = mapper.readTree(resp.body());
                if (arr.isArray() && arr.size() > 0) {
                    return arr.get(0).get("id").asText();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private String sincronizarCreacionKeycloak(String username, String firstName, String lastName, String email, String password, String rolName) {
        String token = obtenerAdminTokenKeycloak();
        if (token == null) return null;

        String baseUrl = resolverKeycloakBaseUrl();
        String realm = resolverTargetRealm();

        try {
            String userJson = mapper.createObjectNode()
                    .put("username", username)
                    .put("firstName", firstName)
                    .put("lastName", lastName)
                    .put("email", email)
                    .put("enabled", true)
                    .put("emailVerified", true)
                    .toString();

            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(userJson))
                    .build();

            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            String userId = null;

            if (createResp.statusCode() == 201) {
                Optional<String> location = createResp.headers().firstValue("Location");
                if (location.isPresent()) {
                    String loc = location.get();
                    userId = loc.substring(loc.lastIndexOf('/') + 1);
                }
            }

            if (userId == null) {
                userId = buscarKeycloakUserId(token, username);
            }

            if (userId != null) {
                setKeycloakPassword(token, userId, password);
                asignarRolKeycloak(token, userId, rolName);
            }

            return userId;
        } catch (Exception e) {
            return null;
        }
    }

    private void setKeycloakPassword(String token, String userId, String password) {
        String baseUrl = resolverKeycloakBaseUrl();
        String realm = resolverTargetRealm();
        try {
            String credJson = mapper.createObjectNode()
                    .put("type", "password")
                    .put("value", password)
                    .put("temporary", false)
                    .toString();

            HttpRequest passReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(credJson))
                    .build();

            httpClient.send(passReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
        }
    }

    private void asignarRolKeycloak(String token, String userId, String rolName) {
        String baseUrl = resolverKeycloakBaseUrl();
        String realm = resolverTargetRealm();
        try {
            HttpRequest roleGet = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles/" + URLEncoder.encode(rolName, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> roleResp = httpClient.send(roleGet, HttpResponse.BodyHandlers.ofString());
            if (roleResp.statusCode() != 200 && "admin".equalsIgnoreCase(rolName)) {
                roleGet = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles/Admin"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                roleResp = httpClient.send(roleGet, HttpResponse.BodyHandlers.ofString());
            }

            if (roleResp.statusCode() == 200) {
                String payload = "[" + roleResp.body() + "]";
                HttpRequest mapReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                httpClient.send(mapReq, HttpResponse.BodyHandlers.ofString());
            }
        } catch (Exception e) {
        }
    }

    private void actualizarPasswordKeycloak(String username, String keycloakId, String nuevaPassword) {
        String token = obtenerAdminTokenKeycloak();
        if (token == null) return;
        String uid = (keycloakId != null && !keycloakId.isEmpty()) ? keycloakId : buscarKeycloakUserId(token, username);
        if (uid != null) {
            setKeycloakPassword(token, uid, nuevaPassword);
        }
    }

    private void actualizarEstadoKeycloak(String username, String keycloakId, boolean enabled) {
        String token = obtenerAdminTokenKeycloak();
        if (token == null) return;
        String uid = (keycloakId != null && !keycloakId.isEmpty()) ? keycloakId : buscarKeycloakUserId(token, username);
        if (uid != null) {
            String baseUrl = resolverKeycloakBaseUrl();
            String realm = resolverTargetRealm();
            try {
                String body = mapper.createObjectNode().put("enabled", enabled).toString();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + uid))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
            }
        }
    }
}