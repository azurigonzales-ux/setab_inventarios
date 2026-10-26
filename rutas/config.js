// Detecta si estás abriendo la página desde tu computadora o desde internet
const esLocal = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost';

const SETAB_CONFIG = {
    // Si es local, usa localhost:8080. Si no, usa el enlace de Render
    backendUrl: esLocal ? 'http://localhost:8080' : 'https://setab-inventarios-c19i.onrender.com',

    // Servidor funcional de Keycloak en Render (común para local y producción)
    keycloakUrl: 'https://keykloak.onrender.com',

    // Como nombraste tu servidor igual, el reino siempre es el mismo en ambos lados
    realm: 'setab-erp',

    clientId: 'setab-frontend'
};