package org.acme;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class InicializadorDatos {

    private static final String TIPO_CONFIG = "SISTEMA_CONFIG";
    private static final String CLAVE_PURGA = "PURGA_PRUEBAS_V1";

    @Inject
    DataSource dataSource;

    public void alIniciar(@Observes StartupEvent evt) {
        boolean purgaEjecutada = false;
        try {
            purgaEjecutada = verificarYEjecutarPurgaUnica();
        } catch (Exception e) {
            System.err.println("Aviso en inicialización de datos: " + e.getMessage());
        }

        boolean articulosVacios = contarArticulosSeguro() == 0;
        boolean resguardosVacios = contarResguardosSeguro() == 0;
        ajustarSecuenciasPostgreSQL(purgaEjecutada || articulosVacios, purgaEjecutada || resguardosVacios);
    }

    @Transactional
    public boolean verificarYEjecutarPurgaUnica() {
        CatalogoGeneral marcador = CatalogoGeneral.find(
                "upper(tipoCatalogo) = ?1 and upper(clave) = ?2",
                TIPO_CONFIG,
                CLAVE_PURGA
        ).firstResult();

        if (marcador != null) {
            return false;
        }

        ejecutarPurgaEntidades();

        CatalogoGeneral nuevoMarcador = new CatalogoGeneral();
        nuevoMarcador.id = null;
        nuevoMarcador.tipoCatalogo = TIPO_CONFIG;
        nuevoMarcador.clave = CLAVE_PURGA;
        nuevoMarcador.nombre = "PURGA INICIAL DE DATOS DE PRUEBA COMPLETADA";
        nuevoMarcador.descripcion = "PURGA INICIAL DE DATOS DE PRUEBA COMPLETADA";
        nuevoMarcador.persist();

        return true;
    }

    @Transactional
    public void ejecutarPurgaEntidades() {
        HistorialTrazabilidad.deleteAll();
        Resguardo.deleteAll();
        Articulo.deleteAll();

        List<Usuario> todosUsuarios = Usuario.listAll();
        Set<Long> empleadosSuperAdminIds = new HashSet<>();
        Set<String> usernamesSuperAdmin = new HashSet<>();

        for (Usuario u : todosUsuarios) {
            boolean esSuperAdmin = (u.rol != null && u.rol.trim().equalsIgnoreCase("SUPERADMIN"))
                    || (u.username != null && u.username.trim().equalsIgnoreCase("superadmin"));
            if (esSuperAdmin) {
                u.rol = "SUPERADMIN";
                u.estado = "Activo";
                u.persist();
                if (u.idEmpleado != null) {
                    empleadosSuperAdminIds.add(u.idEmpleado);
                }
                if (u.username != null && !u.username.trim().isEmpty()) {
                    usernamesSuperAdmin.add(u.username.trim().toUpperCase());
                }
            } else {
                u.delete();
            }
        }

        List<Empleado> todosEmpleados = Empleado.listAll();
        for (Empleado emp : todosEmpleados) {
            boolean conservar = (emp.idEmpleado != null && empleadosSuperAdminIds.contains(emp.idEmpleado))
                    || (emp.usuario != null && usernamesSuperAdmin.contains(emp.usuario.trim().toUpperCase()))
                    || (emp.usuario != null && emp.usuario.trim().equalsIgnoreCase("superadmin"));
            if (!conservar) {
                emp.delete();
            }
        }

        if (Usuario.count("upper(rol) = ?1", "SUPERADMIN") == 0) {
            Usuario superAdmin = new Usuario();
            superAdmin.id = null;
            superAdmin.username = "superadmin";
            superAdmin.nombreCompleto = "ADMINISTRADOR GENERAL SETAB";
            superAdmin.rol = "SUPERADMIN";
            superAdmin.estado = "Activo";
            superAdmin.persist();
        }
    }

    public void purgarDatosPruebaCompleto() {
        ejecutarPurgaEntidades();
        ajustarSecuenciasPostgreSQL(true, true);
    }

    @Transactional
    public long contarArticulosSeguro() {
        try {
            return Articulo.count();
        } catch (Exception e) {
            return -1;
        }
    }

    @Transactional
    public long contarResguardosSeguro() {
        try {
            return Resguardo.count();
        } catch (Exception e) {
            return -1;
        }
    }

    public void ajustarSecuenciasPostgreSQL(boolean reiniciarArticuloSeq, boolean reiniciarResguardoSeq) {
        if (dataSource == null) {
            return;
        }
        String[] secuenciasIncrementoUno = new String[] {
            "ALTER SEQUENCE IF EXISTS Articulo_SEQ INCREMENT BY 1",
            "ALTER SEQUENCE IF EXISTS Resguardo_SEQ INCREMENT BY 1",
            "ALTER SEQUENCE IF EXISTS Usuario_SEQ INCREMENT BY 1",
            "ALTER SEQUENCE IF EXISTS CentroTrabajo_SEQ INCREMENT BY 1",
            "ALTER SEQUENCE IF EXISTS CatalogoGeneral_SEQ INCREMENT BY 1",
            "ALTER SEQUENCE IF EXISTS HistorialTrazabilidad_SEQ INCREMENT BY 1"
        };

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                for (String sql : secuenciasIncrementoUno) {
                    try {
                        stmt.execute(sql);
                    } catch (Exception ignored) {
                    }
                }
                if (reiniciarArticuloSeq) {
                    try {
                        stmt.execute("ALTER SEQUENCE IF EXISTS Articulo_SEQ RESTART WITH 1 INCREMENT BY 1");
                    } catch (Exception ignored) {
                    }
                }
                if (reiniciarResguardoSeq) {
                    try {
                        stmt.execute("ALTER SEQUENCE IF EXISTS Resguardo_SEQ RESTART WITH 1 INCREMENT BY 1");
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Aviso al ajustar secuencias en PostgreSQL: " + e.getMessage());
        }
    }
}