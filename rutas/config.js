// Detecta si estás abriendo la página desde tu computadora o desde internet
const esLocal = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost';

const SETAB_CONFIG = {
    // Si es local, usa la IP de Juan Luis. Si no, usa su enlace de Render
    backendUrl: esLocal ? 'http://192.168.137.1:8080' : 'https://setab-inventarios-c19i.onrender.com',

    // Aquí va la raíz exacta de tu nuevo servidor Keycloak en Render
    keycloakUrl: esLocal ? 'http://localhost:8081' : 'https://keykloak.onrender.com',

    // Como nombraste tu servidor igual, el reino siempre es el mismo en ambos lados
    realm: 'setab-erp',

    clientId: 'setab-frontend'
};