const http = require('http');
const fs = require('fs');
const path = require('path');

const PUERTO = 5500;
const TIPOS_MIME = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
    '.ttf': 'font/ttf'
};

const server = http.createServer((req, res) => {
    let urlLimpia = decodeURI(req.url.split('?')[0]);
    if (urlLimpia === '/' || urlLimpia === '') {
        urlLimpia = '/login/index.html';
    }

    let rutaArchivo = path.join(__dirname, urlLimpia);

    if (fs.existsSync(rutaArchivo) && fs.statSync(rutaArchivo).isDirectory()) {
        const indexHtml = path.join(rutaArchivo, 'index.html');
        if (fs.existsSync(indexHtml)) {
            rutaArchivo = indexHtml;
        }
    }

    fs.readFile(rutaArchivo, (err, data) => {
        if (err) {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            res.end('404 No Encontrado');
            return;
        }
        const ext = path.extname(rutaArchivo).toLowerCase();
        res.writeHead(200, {
            'Content-Type': TIPOS_MIME[ext] || 'application/octet-stream',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': '*'
        });
        res.end(data);
    });
});

server.listen(PUERTO, '127.0.0.1', () => {
    console.log(`Servidor local activo en: http://127.0.0.1:${PUERTO}/login/`);
});
