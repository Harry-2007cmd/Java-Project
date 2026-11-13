package com.campus.parking.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;

/**
 * Serves the static HTML/CSS/JS frontend straight out of the classpath
 * (src/main/resources/static/...). No templating, no build step - just
 * plain files, which is all a college-project frontend needs.
 */
public class StaticFileHandler implements HttpHandler {

    private final String baseResourcePath;
    private final String indexFile;

    public StaticFileHandler(String baseResourcePath, String indexFile) {
        this.baseResourcePath = baseResourcePath;
        this.indexFile = indexFile;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/" + indexFile;
        }
        // Prevent escaping the static folder with "../"
        String safePath = path.replace("..", "");
        String resourcePath = baseResourcePath + safePath;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = in.readAllBytes();
            String contentType = guessContentType(resourcePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        String guess = URLConnection.guessContentTypeFromName(path);
        return guess != null ? guess : "application/octet-stream";
    }
}
