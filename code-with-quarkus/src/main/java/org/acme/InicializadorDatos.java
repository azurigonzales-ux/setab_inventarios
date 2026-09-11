package org.acme;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class InicializadorDatos {

    @Transactional
    public void cargarUsuarios(@Observes StartupEvent evt) {
        // El servidor verifica si ya existe el usuario "raul" para no duplicarlo en cada reinicio
        long conteo = Usuario.count("username", "raul");
        
        if (conteo == 0) {
            Usuario admin = new Usuario();
            admin.username = "raul";
            admin.password = "12345"; 
            
            admin.persist();
            System.out.println("✅ INICIALIZACIÓN: Usuario Administrador 'raul' creado con éxito en la base de datos.");
        } else {
            System.out.println("✅ INICIALIZACIÓN: El usuario 'raul' ya existe. Omitiendo creación.");
        }
    }
}