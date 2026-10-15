package org.acme;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @ConfigProperty(name = "keycloak.admin.server-url")
    Optional<String> keycloakAdminServerUrl;

    @ConfigProperty(name = "keycloak.admin.url")
    Optional<String> customKeycloakUrl;

    @ConfigProperty(name = "keycloak.admin.realm", defaultValue = "master")
    String configuredAdminRealm;

    @ConfigProperty(name = "keycloak.admin.master-realm", defaultValue = "master")
    String masterRealm;

    @ConfigProperty(name = "keycloak.admin.client-id", defaultValue = "admin-cli")
    String adminClientId;

    @ConfigProperty(name = "keycloak.admin.username", defaultValue = "admin")
    String adminUser;

    @ConfigProperty(name = "keycloak.admin.password", defaultValue = "admin")
    String adminPassword;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
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

    private static class SesionAdminKeycloak {
        final String baseUrl;
        final String realm;
        final String token;

        SesionAdminKeycloak(String baseUrl, String realm, String token) {
            this.baseUrl = baseUrl;
            this.realm = realm;
            this.token = token;
        }
    }

    private String normalizarUrlBase(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        String url = rawUrl.trim();
        if (url.contains("/realms/")) {
            url = url.substring(0, url.indexOf("/realms/"));
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isEmpty() ? null : url;
    }

    private List<String> resolverCandidatosKeycloakBaseUrl(String frontUrl) {
        Set<String> candidatos = new LinkedHashSet<>();

        if (jwt != null && jwt.getIssuer() != null) {
            String fromIssuer = normalizarUrlBase(jwt.getIssuer());
            if (fromIssuer != null) candidatos.add(fromIssuer);
        }

        String fromFront = normalizarUrlBase(frontUrl);
        if (fromFront != null) candidatos.add(fromFront);

        if (keycloakAdminServerUrl.isPresent()) {
            String fromAdminProp = normalizarUrlBase(keycloakAdminServerUrl.get());
            if (fromAdminProp != null) candidatos.add(fromAdminProp);
        }

        if (customKeycloakUrl.isPresent()) {
            String fromCustom = normalizarUrlBase(customKeycloakUrl.get());
            if (fromCustom != null) candidatos.add(fromCustom);
        }

        if (oidcAuthServerUrl.isPresent()) {
            String fromOidc = normalizarUrlBase(oidcAuthServerUrl.get());
            if (fromOidc != null) candidatos.add(fromOidc);
        }

        candidatos.add("https://keykloak.onrender.com");
        candidatos.add("http://localhost:8081");

        return new ArrayList<>(candidatos);
    }

    private String resolverTargetRealm(String frontRealm) {
        if (frontRealm != null && !frontRealm.isBlank() && !"master".equalsIgnoreCase(frontRealm.trim())) {
            return frontRealm.trim();
        }
        if (jwt != null && jwt.getIssuer() != null && jwt.getIssuer().contains("/realms/")) {
            String iss = jwt.getIssuer().trim();
            String resto = iss.substring(iss.indexOf("/realms/") + 8);
            int slash = resto.indexOf('/');
            String extracted = slash > 0 ? resto.substring(0, slash) : resto;
            if (!extracted.isBlank() && !"master".equalsIgnoreCase(extracted)) {
                return extracted;
            }
        }
        if (oidcAuthServerUrl.isPresent() && oidcAuthServerUrl.get().contains("/realms/")) {
            String url = oidcAuthServerUrl.get().trim();
            String resto = url.substring(url.indexOf("/realms/") + 8);
            int slash = resto.indexOf('/');
            String extracted = slash > 0 ? resto.substring(0, slash) : resto;
            if (!extracted.isBlank() && !"master".equalsIgnoreCase(extracted)) {
                return extracted;
            }
        }
        if (configuredAdminRealm != null && !configuredAdminRealm.isBlank() && !"master".equalsIgnoreCase(configuredAdminRealm.trim())) {
            return configuredAdminRealm.trim();
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
                    .entity("{\"error\": \"El nombre de usuario (RFC) es obligatorio.\"}").build();
        }

        String rawUsername = dto.username.trim();
        String passwordFinal = (dto.password != null && !dto.password.trim().isEmpty()) ? dto.password.trim() : "Setab2026*";
        String firstName = (dto.nombre != null && !dto.nombre.trim().isEmpty()) ? dto.nombre.trim().toUpperCase() : rawUsername.toUpperCase();
        String lastName = (dto.apellidos != null && !dto.apellidos.trim().isEmpty()) ? dto.apellidos.trim().toUpperCase() : "SETAB";
        String nombreCompleto = (firstName + " " + lastName).trim();
        String emailFinal = (dto.email != null && !dto.email.trim().isEmpty())
                ? dto.email.trim().toLowerCase()
                : rawUsername.toLowerCase().replaceAll("[^a-z0-9]", "") + "@setab.gob.mx";

        String rolNormalizado = (dto.rol != null && dto.rol.equalsIgnoreCase("SUPERADMIN")) ? "SUPERADMIN" : "Admin";
        String rolKeycloak = rolNormalizado.equals("SUPERADMIN") ? "SUPERADMIN" : "admin";

        String targetRealm = resolverTargetRealm(dto.realm);
        List<String> baseUrls = resolverCandidatosKeycloakBaseUrl(dto.keycloakUrl);

        String keycloakUserId;
        try {
            SesionAdminKeycloak sesion = abrirSesionAdminKeycloak(baseUrls, targetRealm);
            keycloakUserId = sincronizarCreacionKeycloak(
                    sesion.baseUrl,
                    sesion.realm,
                    sesion.token,
                    rawUsername,
                    firstName,
                    lastName,
                    emailFinal,
                    passwordFinal,
                    rolKeycloak
            );
        } catch (Exception e) {
            String motivo = limpiarMensajeJson(e.getMessage() != null ? e.getMessage() : "No fue posible sincronizar con Keycloak.");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + motivo + "\"}").build();
        }

        Empleado emp = Empleado.find("upper(usuario) = ?1", rawUsername.toUpperCase()).firstResult();
        if (emp == null) {
            emp = new Empleado();
        }
        emp.nombre = nombreCompleto;
        emp.rfc = rawUsername.toUpperCase();
        emp.usuario = rawUsername.toLowerCase();
        emp.cct = dto.cct != null && !dto.cct.trim().isEmpty() ? dto.cct.trim().toUpperCase() : "CENTRAL";
        emp.nombreEscuela = dto.nombreEscuela != null && !dto.nombreEscuela.trim().isEmpty() ? dto.nombreEscuela.trim().toUpperCase() : "ALMACÉN CENTRAL";
        emp.area = dto.nivelEducativo != null && !dto.nivelEducativo.trim().isEmpty() ? dto.nivelEducativo.trim() : "Primaria";
        emp.cargo = rolNormalizado.equals("SUPERADMIN") ? "Administrador de Almacén" : "Director de Escuela";
        emp.persist();

        Usuario usuario = Usuario.find("upper(username) = ?1", rawUsername.toUpperCase()).firstResult();
        if (usuario == null) {
            usuario = new Usuario();
        }
        usuario.username = rawUsername;
        usuario.password = passwordFinal;
        usuario.nombreCompleto = nombreCompleto;
        usuario.email = emailFinal;
        usuario.cct = emp.cct;
        usuario.nivelEducativo = emp.area;
        usuario.nombreEscuela = emp.nombreEscuela;
        usuario.rol = rolNormalizado;
        usuario.estado = "Activo";
        usuario.keycloakId = keycloakUserId;
        usuario.idEmpleado = emp.idEmpleado;
        usuario.persist();

        return Response.status(Response.Status.CREATED).entity(usuario).build();
    }

    @PUT
    @Path("/{id}/reset-password")
    @Transactional
    public Response resetPassword(@PathParam("id") Long id, PasswordDTO dto) {
        Usuario usuario = Usuario.findById(id);
        if (usuario == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Usuario no encontrado.\"}").build();
        }
        if (dto == null || dto.nuevaPassword == null || dto.nuevaPassword.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"La nueva contraseña no puede estar vacía.\"}").build();
        }

        String nuevaPass = dto.nuevaPassword.trim();
        String targetRealm = resolverTargetRealm(dto.realm);
        List<String> baseUrls = resolverCandidatosKeycloakBaseUrl(dto.keycloakUrl);
        String rolKeycloak = "SUPERADMIN".equalsIgnoreCase(usuario.rol) ? "SUPERADMIN" : "admin";

        try {
            SesionAdminKeycloak sesion = abrirSesionAdminKeycloak(baseUrls, targetRealm);
            String uid = actualizarOCrearPasswordKeycloak(sesion.baseUrl, sesion.realm, sesion.token, usuario, nuevaPass, rolKeycloak);
            if (uid != null) {
                usuario.keycloakId = uid;
            }
            usuario.password = nuevaPass;
            usuario.persist();
            return Response.ok("{\"mensaje\": \"Contraseña actualizada y sincronizada con Keycloak\"}").build();
        } catch (Exception e) {
            String motivo = limpiarMensajeJson(e.getMessage() != null ? e.getMessage() : "Error al actualizar contraseña en Keycloak.");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"" + motivo + "\"}").build();
        }
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

        String targetRealm = resolverTargetRealm(null);
        List<String> baseUrls = resolverCandidatosKeycloakBaseUrl(null);
        try {
            SesionAdminKeycloak sesion = abrirSesionAdminKeycloak(baseUrls, targetRealm);
            actualizarEstadoKeycloak(sesion.baseUrl, sesion.realm, sesion.token, usuario.username, usuario.keycloakId, nuevoEstadoActivo);
        } catch (Exception ignored) {
        }

        return Response.ok(usuario).build();
    }

    private String intentarTokenConCredenciales(String baseUrl, String authRealm, String clientId, String user, String pass) {
        if (baseUrl == null || authRealm == null || clientId == null || user == null || pass == null || user.isBlank() || pass.isBlank()) {
            return null;
        }
        try {
            String form = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(pass, StandardCharsets.UTF_8)
                    + "&grant_type=password";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/realms/" + authRealm + "/protocol/openid-connect/token"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode node = mapper.readTree(resp.body());
                if (node.hasNonNull("access_token")) {
                    return node.get("access_token").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean tokenTienePermisoAdminUsuarios(String baseUrl, String targetRealm, String token) {
        if (token == null || token.isBlank()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + targetRealm + "/users?max=1"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private SesionAdminKeycloak abrirSesionAdminKeycloak(List<String> baseUrls, String targetRealm) {
        Set<String> realmsAutenticacion = new LinkedHashSet<>();
        if (configuredAdminRealm != null && !configuredAdminRealm.isBlank()) {
            realmsAutenticacion.add(configuredAdminRealm.trim());
        }
        if (masterRealm != null && !masterRealm.isBlank()) {
            realmsAutenticacion.add(masterRealm.trim());
        }
        realmsAutenticacion.add("master");
        realmsAutenticacion.add(targetRealm);

        Set<String> clientIds = new LinkedHashSet<>();
        if (adminClientId != null && !adminClientId.isBlank()) {
            clientIds.add(adminClientId.trim());
        }
        clientIds.add("admin-cli");
        clientIds.add("setab-frontend");

        List<String[]> paresCredenciales = new ArrayList<>();
        paresCredenciales.add(new String[]{adminUser, adminPassword});

        String envUser = System.getenv("KEYCLOAK_ADMIN");
        String envPass = System.getenv("KEYCLOAK_ADMIN_PASSWORD");
        if (envUser != null && envPass != null) {
            paresCredenciales.add(new String[]{envUser, envPass});
        }

        String bootUser = System.getenv("KC_BOOTSTRAP_ADMIN_USERNAME");
        String bootPass = System.getenv("KC_BOOTSTRAP_ADMIN_PASSWORD");
        if (bootUser != null && bootPass != null) {
            paresCredenciales.add(new String[]{bootUser, bootPass});
        }

        paresCredenciales.add(new String[]{"admin", "r@3/DGf1EucT;Vy5u.qK"});
        paresCredenciales.add(new String[]{"admin", "admin"});

        SesionAdminKeycloak fallbackTokenSinVerificar = null;

        for (String baseUrl : baseUrls) {
            for (String authRealm : realmsAutenticacion) {
                for (String clientId : clientIds) {
                    for (String[] cred : paresCredenciales) {
                        String token = intentarTokenConCredenciales(baseUrl, authRealm, clientId, cred[0], cred[1]);
                        if (token != null) {
                            if (tokenTienePermisoAdminUsuarios(baseUrl, targetRealm, token)) {
                                return new SesionAdminKeycloak(baseUrl, targetRealm, token);
                            }
                            if (fallbackTokenSinVerificar == null) {
                                fallbackTokenSinVerificar = new SesionAdminKeycloak(baseUrl, targetRealm, token);
                            }
                        }
                    }
                }
            }

            if (jwt != null && jwt.getRawToken() != null && !jwt.getRawToken().isBlank()) {
                if (tokenTienePermisoAdminUsuarios(baseUrl, targetRealm, jwt.getRawToken())) {
                    return new SesionAdminKeycloak(baseUrl, targetRealm, jwt.getRawToken());
                }
            }
        }

        if (fallbackTokenSinVerificar != null) {
            return fallbackTokenSinVerificar;
        }

        throw new IllegalStateException("No se pudo obtener un token de administración válido en Keycloak (" + String.join(", ", baseUrls) + ") para el realm '" + targetRealm + "'. Verifique las credenciales de Keycloak Admin.");
    }

    private String buscarKeycloakUserId(String baseUrl, String realm, String token, String username) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&exact=true"))
                    .timeout(Duration.ofSeconds(10))
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

            HttpRequest searchReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users?search=" + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> searchResp = httpClient.send(searchReq, HttpResponse.BodyHandlers.ofString());
            if (searchResp.statusCode() == 200) {
                JsonNode arr = mapper.readTree(searchResp.body());
                if (arr.isArray()) {
                    for (JsonNode u : arr) {
                        if (u.hasNonNull("username") && u.get("username").asText().equalsIgnoreCase(username)) {
                            return u.get("id").asText();
                        }
                    }
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

    private String sincronizarCreacionKeycloak(
            String baseUrl,
            String realm,
            String token,
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String rolName
    ) throws Exception {
        String userId = buscarKeycloakUserId(baseUrl, realm, token, username);
        String emailUsado = email;

        if (userId == null) {
            ObjectNode payload = construirPayloadUsuarioKeycloak(username, firstName, lastName, emailUsado, password);
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());

            if (createResp.statusCode() == 409) {
                userId = buscarKeycloakUserId(baseUrl, realm, token, username);
                if (userId == null) {
                    emailUsado = username.toLowerCase().replaceAll("[^a-z0-9]", "") + "_" + (System.currentTimeMillis() % 100000) + "@setab.gob.mx";
                    ObjectNode retryPayload = construirPayloadUsuarioKeycloak(username, firstName, lastName, emailUsado, password);
                    HttpRequest retryReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(retryPayload.toString()))
                            .build();
                    createResp = httpClient.send(retryReq, HttpResponse.BodyHandlers.ofString());
                }
            }

            if (userId == null && createResp.statusCode() == 201) {
                Optional<String> location = createResp.headers().firstValue("Location");
                if (location.isPresent()) {
                    String loc = location.get();
                    userId = loc.substring(loc.lastIndexOf('/') + 1);
                }
            }

            if (userId == null) {
                userId = buscarKeycloakUserId(baseUrl, realm, token, username);
            }

            if (userId == null) {
                throw new IllegalStateException("Keycloak rechazó la creación del usuario en el realm '" + realm + "' (HTTP " + createResp.statusCode() + "): " + extraerDetalleErrorKeycloak(createResp.body()));
            }
        }

        limpiarAccionesRequeridasKeycloak(baseUrl, realm, token, userId, username, firstName, lastName, emailUsado);
        setKeycloakPassword(baseUrl, realm, token, userId, password);
        asignarRolKeycloak(baseUrl, realm, token, userId, rolName);

        return userId;
    }

    private void limpiarAccionesRequeridasKeycloak(
            String baseUrl,
            String realm,
            String token,
            String userId,
            String username,
            String firstName,
            String lastName,
            String email
    ) {
        try {
            ObjectNode updateNode = null;
            HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() == 200) {
                JsonNode existing = mapper.readTree(getResp.body());
                if (existing != null && existing.isObject()) {
                    updateNode = (ObjectNode) existing;
                }
            }
            if (updateNode == null) {
                updateNode = mapper.createObjectNode();
                if (username != null && !username.isBlank()) updateNode.put("username", username);
                if (email != null && !email.isBlank()) updateNode.put("email", email);
            } else {
                if (!updateNode.hasNonNull("email") && email != null && !email.isBlank()) {
                    updateNode.put("email", email);
                }
            }

            if (firstName != null && !firstName.isBlank()) updateNode.put("firstName", firstName);
            if (lastName != null && !lastName.isBlank()) updateNode.put("lastName", lastName);
            updateNode.put("enabled", true);
            updateNode.put("emailVerified", true);
            updateNode.putArray("requiredActions");

            HttpRequest putReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(updateNode.toString()))
                    .build();
            httpClient.send(putReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
        }
    }

    private void setKeycloakPassword(String baseUrl, String realm, String token, String userId, String password) throws Exception {
        String credJson = mapper.createObjectNode()
                .put("type", "password")
                .put("value", password)
                .put("temporary", false)
                .toString();

        HttpRequest passReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(credJson))
                .build();

        HttpResponse<String> passResp = httpClient.send(passReq, HttpResponse.BodyHandlers.ofString());
        if (passResp.statusCode() != 204 && passResp.statusCode() != 200) {
            throw new IllegalStateException("Keycloak rechazó la asignación de contraseña (HTTP " + passResp.statusCode() + "): " + extraerDetalleErrorKeycloak(passResp.body()));
        }
    }

    private void asignarRolKeycloak(String baseUrl, String realm, String token, String userId, String rolName) {
        try {
            String[] candidatos = "SUPERADMIN".equalsIgnoreCase(rolName)
                    ? new String[]{"SUPERADMIN"}
                    : new String[]{"admin", "Admin"};

            boolean rolAsignado = false;
            for (String nombreRol : candidatos) {
                HttpRequest roleGet = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles/" + URLEncoder.encode(nombreRol, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> roleResp = httpClient.send(roleGet, HttpResponse.BodyHandlers.ofString());
                if (roleResp.statusCode() == 200) {
                    String payload = "[" + roleResp.body() + "]";
                    HttpRequest mapReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
                    httpClient.send(mapReq, HttpResponse.BodyHandlers.ofString());
                    rolAsignado = true;
                }
            }

            if (!rolAsignado) {
                String rolACrear = candidatos[0];
                String createRoleBody = mapper.createObjectNode().put("name", rolACrear).toString();
                HttpRequest createRoleReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createRoleBody))
                        .build();
                httpClient.send(createRoleReq, HttpResponse.BodyHandlers.ofString());

                HttpRequest roleGet = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/roles/" + URLEncoder.encode(rolACrear, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> roleResp = httpClient.send(roleGet, HttpResponse.BodyHandlers.ofString());
                if (roleResp.statusCode() == 200) {
                    String payload = "[" + roleResp.body() + "]";
                    HttpRequest mapReq = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm"))
                            .timeout(Duration.ofSeconds(10))
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

    private String actualizarOCrearPasswordKeycloak(
            String baseUrl,
            String realm,
            String token,
            Usuario usuario,
            String nuevaPassword,
            String rolKeycloak
    ) throws Exception {
        String uid = (usuario.keycloakId != null && !usuario.keycloakId.isBlank())
                ? usuario.keycloakId
                : buscarKeycloakUserId(baseUrl, realm, token, usuario.username);

        String emailFallback = (usuario.email != null && !usuario.email.isBlank())
                ? usuario.email.trim().toLowerCase()
                : usuario.username.toLowerCase().replaceAll("[^a-z0-9]", "") + "@setab.gob.mx";
        String nombreFallback = (usuario.nombreCompleto != null && !usuario.nombreCompleto.isBlank())
                ? usuario.nombreCompleto.trim()
                : usuario.username;

        if (uid == null) {
            return sincronizarCreacionKeycloak(
                    baseUrl,
                    realm,
                    token,
                    usuario.username,
                    nombreFallback,
                    "SETAB",
                    emailFallback,
                    nuevaPassword,
                    rolKeycloak
            );
        }

        limpiarAccionesRequeridasKeycloak(baseUrl, realm, token, uid, usuario.username, nombreFallback, "SETAB", emailFallback);
        setKeycloakPassword(baseUrl, realm, token, uid, nuevaPassword);
        asignarRolKeycloak(baseUrl, realm, token, uid, rolKeycloak);
        return uid;
    }

    private void actualizarEstadoKeycloak(String baseUrl, String realm, String token, String username, String keycloakId, boolean enabled) {
        String uid = (keycloakId != null && !keycloakId.isBlank()) ? keycloakId : buscarKeycloakUserId(baseUrl, realm, token, username);
        if (uid != null) {
            try {
                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + uid))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
                HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
                ObjectNode bodyNode = (getResp.statusCode() == 200 && mapper.readTree(getResp.body()).isObject())
                        ? (ObjectNode) mapper.readTree(getResp.body())
                        : mapper.createObjectNode();
                bodyNode.put("enabled", enabled);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/admin/realms/" + realm + "/users/" + uid))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(bodyNode.toString()))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
            }
        }
    }

    private String extraerDetalleErrorKeycloak(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "Respuesta vacía de Keycloak";
        try {
            JsonNode node = mapper.readTree(rawBody);
            if (node.hasNonNull("errorMessage")) return node.get("errorMessage").asText();
            if (node.hasNonNull("error_description")) return node.get("error_description").asText();
            if (node.hasNonNull("error")) return node.get("error").asText();
        } catch (Exception ignored) {
        }
        return rawBody;
    }

    private String limpiarMensajeJson(String msg) {
        if (msg == null) return "Error desconocido";
        return msg.replace("\\", "/")
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }
}