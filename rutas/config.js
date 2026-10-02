// Detecta si estás abriendo la página desde tu computadora o desde internet
const esLocal = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost';

const SETAB_CONFIG = {
    // Si es local, usa la IP de Juanluis. Si no, usa su enlace de Render
    backendUrl: 'https://setab-inventarios.onrender.com',

    // Aquí va la raíz exacta de tu servidor Cloud-IAM
    keycloakUrl: esLocal ? 'http://localhost:8081' : 'https://lemur-17.cloud-iam.com/auth',

    // Como nombraste tu servidor igual, el reino siempre es el mismo en ambos lados
    realm: 'setab-erp',

    clientId: 'setab-frontend'
};