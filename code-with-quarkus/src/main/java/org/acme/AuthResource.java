package org.acme;

import java.util.Arrays;
import java.util.HashSet;

import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class AuthResource {

    @POST
    @Path("/registrar")
    @Transactional
    public Response registrar(Usuario nuevoUsuario) {
        nuevoUsuario.persist();
        return Response.status(Response.Status.CREATED).entity(nuevoUsuario).build();
    }

    @POST
    @Path("/login")
    public Response login(Usuario credenciales) {
        Usuario usuarioDB = Usuario.find("username", credenciales.username).firstResult();

        if (usuarioDB != null && usuarioDB.password.equals(credenciales.password)) {
            
            if ("Inactivo".equalsIgnoreCase(usuarioDB.estado)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                               .entity("{\"error\": \"Usuario inactivo. Contacte al administrador principal.\"}").build();
            }

            String rolUsuario = (usuarioDB.rol != null && !usuarioDB.rol.trim().isEmpty()) ? usuarioDB.rol : "Admin";

            String token = Jwt.issuer("sistema-inventario-setab")
                              .upn(usuarioDB.username)
                              .groups(new HashSet<>(Arrays.asList("Admin", rolUsuario)))
                              .expiresIn(7200)
                              .sign();

            return Response.ok("{\"token\": \"" + token + "\", \"rol\": \"" + rolUsuario + "\"}").build();
        } else {
            return Response.status(Response.Status.UNAUTHORIZED)
                           .entity("{\"error\": \"Credenciales incorrectas\"}").build();
        }
    }
}