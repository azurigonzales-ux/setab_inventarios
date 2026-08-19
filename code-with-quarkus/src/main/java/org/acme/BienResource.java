package org.acme;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

@Path("/bienes")
public class BienResource {

    @GET
    @Path("/acuse") 
    @Produces(MediaType.TEXT_HTML)
    public String generarAcuse() throws Exception {
        
        // ¡EL TRUCO DE ORO ESTÁ AQUÍ! 
        // Le pasamos el ClassLoader de la "burbuja" de Quarkus para que pueda ver la carpeta templates
        ClasspathLoader loader = new ClasspathLoader(Thread.currentThread().getContextClassLoader());
        
        // Configuramos el motor
        PebbleEngine engine = new PebbleEngine.Builder().loader(loader).build();
        
        // Cargamos tu archivo HTML
        PebbleTemplate compiledTemplate = engine.getTemplate("templates/acuse-resguardo.html");
        
        // Preparamos los datos
        Map<String, Object> contexto = new HashMap<>();
        contexto.put("num_inventario", "SETAB-2026-001");
        contexto.put("tipo_bien", "Laptop ASUS TUF Gaming");
        contexto.put("responsable", "Juan Luis");
        
        // Mezclamos los datos con el HTML
        Writer writer = new StringWriter();
        compiledTemplate.evaluate(writer, contexto);
        
        // Devolvemos el HTML ya armado
        return writer.toString();
    }
}