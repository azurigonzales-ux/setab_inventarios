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
import org.eclipse.microprofile.jwt.JsonWebToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Inject
    JsonWebToken jwt;

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
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @JsonIgnoreProperties(ignoreUnknown = true)
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
        public String keycloakUrl;
        public String realm;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PasswordDTO {
        public String nuevaPassword;
        public String keycloakUrl;
        public String realm;
    }

    private String resolverKeycloakBaseUrl(String frontUrl) {
        String candidate = null;
        if (frontUrl != null && !frontUrl.isBlank()) {
            candidate = frontUrl.trim();
        } else if (customKeycloakUrl.isPresent() && !customKeycloakUrl.get().isBlank()) {
            candidate = customKeycloakUrl.get().trim();
        } else if (oidcAuthServerUrl.isPresent() && oidcAuthServerUrl.get().contains("/realms/")) {
            String url = oidcAuthServerUrl.get().trim();
            candidate = url.substring(0, url.indexOf("/realms/"));
        }
        if (candidate == null || candidate.isEmpty()) {
            candidate = "http://localhost:8080";
        }
        if (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private String resolverTargetRealm(String frontRealm) {
        if (frontRealm != null && !frontRealm.isBlank()) {
            return frontRealm.trim();
        }
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
                    u.username = emp.usuario.trim();
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

        String rawUsername = dto.username.trim();
        Usuario existente = Usuario.find("upper(username) = ?1", rawUsername.toUpperCase()).firstResult();
        if (existente != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"El usuario o RFC ya está registrado en el sistema.\"}").build();
        }

        String nombreCompleto = ((dto.nombre != null ? dto.nombre.trim() : "") + " " + (dto.apellidos != null ? dto.apellidos.trim() : "")).trim().toUpperCase();
        String rolNormalizado = (dto.rol != null && dto.rol.equalsIgnoreCase("SUPERADMIN")) ? "SUPERADMIN" : "Admin";
        String rolKeycloak = rolNormalizado.equals("SUPERADMIN") ? "SUPERADMIN" : "admin";

        String baseUrl = resolverKeycloakBaseUrl(dto.keycloakUrl);
        String realm = resolverTargetRealm(dto.realm);

        String keycloakUserId = sincronizarCreacionKeycloak(
                baseUrl,
                realm,
                rawUsername,
                dto.nombre != null ? dto.nombre.trim().toUpperCase() : rawUsername.toUpperCase(),
                dto.apellidos != null ? dto.apellidos.trim().toUpperCase() : "SETAB",
                dto.email != null && !dto.email.trim().isEmpty() ? dto.email.trim().toLowerCase() : rawUsername.toLowerCase() + "@setab.gob.mx",
                dto.password != null && !dto.password.trim().isEmpty() ? dto.password.trim() : "Setab2026*",
                rolKeycloak
        );

        Empleado emp = Empleado.find("upper(usuario) = ?1", rawUsername.toUpperCase()).firstResult();
        if (emp == null) {
            emp = new Empleado();
        }
        emp.nombre = nombreCompleto;
        emp.rfc = rawUsername.toUpperCase();
        emp.usuario = rawUsername.toLowerCase();
        emp.cct = dto.cct != null ? dto.cct.trim().toUpperCase() : "CENTRAL";
        emp.nombreEscuela = dto.nombreEscuela != null ? dto.nombreEscuela.trim().toUpperCase() : "ALMACÉN CENTRAL";
        emp.area = dto.nivelEducativo != null ? dto.nivelEducativo : "Área Central";
        emp.cargo = rolNormalizado.equals("SUPERADMIN") ? "Administrador de Almacén" : "Director de Escuela";
        emp.persist();

        Usuario nuevo = new Usuario();
        nuevo.username = rawUsername;
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
        String baseUrl = resolverKeycloakBaseUrl(dto.keycloakUrl);
        String realm = resolverTargetRealm(dto.realm);
        String rolKeycloak = "SUPERADMIN".equalsIgnoreCase(usuario.rol) ? "SUPERADMIN" : "admin";

        String uid = actualizarOCrearPasswordKeycloak(baseUrl, realm, usuario, dto.nuevaPassword, rolKeycloak);
        if (uid != null) {
            usuario.keycloakId = uid;
        }
        usuario.persist();

        return Response.ok("{\"mensaje\": \"Contraseña actualizada y sincronizada con Keycloak\"}").build();
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

        String baseUrl = resolverKeycloakBaseUrl(null);
        String realm = resolverTargetRealm(null);
        actualizarEstadoKeycloak(baseUrl, realm, usuario.username, usuario.keycloakId, nuevoEstadoActivo);

        return Response.ok(usuario).build();
    }

    private String intentarTokenConCredenciales(String baseUrl, String targetRealmName, String clientId, String user, String pass) {
        if (user == null || pass == null) return null;
        try {
            String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8)
                    + "&grant_type=password";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/realms/" + targetRealmName + "/protocol/openid-connect/token"))
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
        } catch (Exception ignored) {
        }
        return null;
    }

    private String obtenerAdminTokenKeycloak(String baseUrl, String realm) {
        String token = intentarTokenConCredenciales(baseUrl, adminRealm, adminClientId, adminUser, adminPassword);
        if (token != null) return token;

        String envUser = System.getenv("KEYCLOAK_ADMIN");
        String envPass = System.getenv("KEYCLOAK_ADMIN_PASSWORD");
        token = intentarTokenConCredenciales(baseUrl, adminRealm, adminClientId, envUser, envPass);
        if (token != null) return token;

        String kcBootUser = System.getenv("KC_BOOTSTRAP_ADMIN_USERNAME");
        String kcBootPass = System.getenv("KC_BOOTSTRAP_ADMIN_PASSWORD");
        token = intentarTokenConCredenciales(baseUrl, adminRealm, adminClientId, kcBootUser, kcBootPass);
        if (token != null) return token;

        if (jwt != null && jwt.getRawToken() != null && !jwt.getRawToken().isEmpty()) {
            return jwt.getRawToken();
        }
        return null;
    }

    private String buscarKeycloakUserId(String baseUrl, String realm, String token, String username) {
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
        } catch (Exception ignored) {
        }
        return null;
    }

    private ObjectNode construirPayloadUsuarioKeycloak(String username, String firstName, String lastName, String email, String password) {
        ObjectNode userNode = mapper.createObjectNode();
        userNode.put("username", username);
        userNode.put("firstName", firstName != null && !firstName.isEmpty() ? firstName : username);
        userNode.put("lastName", lastName != null && !lastName.isEmpty() ? lastName : "SETAB");
        userNode.put("email", email);
        userNode.put("enabled", true);
        userNode.put("emailVerified", true);
        userNode.putArray("requiredActions");

        ArrayNode creds = userNode.putArray("credentials");
        ObjectNode cred = creds.addObject();
        cred.put("type", "password");
        cred.put("value", password);
        cred.put("temporary", false);

        return userNode;
    }

    private String sincronizarCreacionKeycloak(String baseUrl, String realm, String username, String firstName, String lastName, String email, String password, String rolName) {
        String token = obtenerAdminTokenKeycloak(baseUrl, realm);
        if (token == null) return null;

        try {
            String userId = buscarKeycloakUserId(baseUrl, realm, token, username);

            if (userId == null) {
                ObjectNode payload = construirPayloadUsuarioKeycloak(username, firstName, lastName, email, password);
                HttpRequest createReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());

                if (createResp.statusCode() == 409) {
                    String uniqueEmail = username.toLowerCase().replaceAll("[^a-z0-9]", "") + "_" + (System.currentTimeMillis() % 100000) + "@setab.gob.mx";
                    ObjectNode retryPayload = construirPayloadUsuarioKeycloak(username, firstName, lastName, uniqueEmail, password);
                    HttpRequest retryReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(retryPayload.toString()))
                            .build();
                    createResp = httpClient.send(retryReq, HttpResponse.BodyHandlers.ofString());
                }

                if (createResp.statusCode() == 201) {
                    Optional<String> location = createResp.headers().firstValue("Location");
                    if (location.isPresent()) {
                        String loc = location.get();
                        userId = loc.substring(loc.lastIndexOf('/') + 1);
                    }
                }
            }

            if (userId == null) {
                userId = buscarKeycloakUserId(baseUrl, realm, token, username);
            }

            if (userId != null) {
                limpiarAccionesRequeridasKeycloak(baseUrl, realm, token, userId, firstName, lastName);
                setKeycloakPassword(baseUrl, realm, token, userId, password);
                asignarRolKeycloak(baseUrl, realm, token, userId, rolName);
            }

            return userId;
        } catch (Exception e) {
            return null;
        }
    }

    private void limpiarAccionesRequeridasKeycloak(String baseUrl, String realm, String token, String userId, String firstName, String lastName) {
        try {
            ObjectNode updateNode = mapper.createObjectNode();
            if (firstName != null && !firstName.isEmpty()) updateNode.put("firstName", firstName);
            if (lastName != null && !lastName.isEmpty()) updateNode.put("lastName", lastName);
            updateNode.put("enabled", true);
            updateNode.put("emailVerified", true);
            updateNode.putArray("requiredActions");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(updateNode.toString()))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
        }
    }

    private void setKeycloakPassword(String baseUrl, String realm, String token, String userId, String password) {
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
        } catch (Exception ignored) {
        }
    }

    private void asignarRolKeycloak(String baseUrl, String realm, String token, String userId, String rolName) {
        try {
            String[] candidatos = "SUPERADMIN".equalsIgnoreCase(rolName)
                    ? new String[]{"SUPERADMIN"}
                    : new String[]{"admin", "Admin"};

            for (String nombreRol : candidatos) {
                HttpRequest roleGet = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles/" + URLEncoder.encode(nombreRol, StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> roleResp = httpClient.send(roleGet, HttpResponse.BodyHandlers.ofString());
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
            }
        } catch (Exception ignored) {
        }
    }

    private String actualizarOCrearPasswordKeycloak(String baseUrl, String realm, Usuario usuario, String nuevaPassword, String rolKeycloak) {
        String token = obtenerAdminTokenKeycloak(baseUrl, realm);
        if (token == null) return null;

        String uid = (usuario.keycloakId != null && !usuario.keycloakId.isEmpty())
                ? usuario.keycloakId
                : buscarKeycloakUserId(baseUrl, realm, token, usuario.username);

        if (uid == null) {
            return sincronizarCreacionKeycloak(
                    baseUrl,
                    realm,
                    usuario.username,
                    usuario.nombreCompleto != null ? usuario.nombreCompleto : usuario.username,
                    "SETAB",
                    usuario.email != null ? usuario.email : usuario.username.toLowerCase() + "@setab.gob.mx",
                    nuevaPassword,
                    rolKeycloak
            );
        }

        limpiarAccionesRequeridasKeycloak(baseUrl, realm, token, uid, usuario.nombreCompleto, "SETAB");
        setKeycloakPassword(baseUrl, realm, token, uid, nuevaPassword);
        asignarRolKeycloak(baseUrl, realm, token, uid, rolKeycloak);
        return uid;
    }

    private void actualizarEstadoKeycloak(String baseUrl, String realm, String username, String keycloakId, boolean enabled) {
        String token = obtenerAdminTokenKeycloak(baseUrl, realm);
        if (token == null) return;
        String uid = (keycloakId != null && !keycloakId.isEmpty()) ? keycloakId : buscarKeycloakUserId(baseUrl, realm, token, username);
        if (uid != null) {
            try {
                String body = mapper.createObjectNode().put("enabled", enabled).toString();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + uid))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
            }
        }
    }
}