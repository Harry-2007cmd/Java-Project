package com.campus.parking.web;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Entry point for the web mode of the application.
 *
 * Deliberately uses the JDK's built-in com.sun.net.httpserver.HttpServer
 * instead of a framework like Spring - this keeps the project pure Java
 * (nothing to explain beyond "it listens on a port and routes requests"),
 * while still serving a real HTML/CSS/JS frontend backed by a JSON REST API.
 */
public class Server {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getProperty("server.port", System.getenv().getOrDefault("PORT", "8080")));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // REST API - everything under /api/*
        server.createContext("/api/slots", new ApiHandlers.SlotsHandler());
        server.createContext("/api/park", new ApiHandlers.ParkHandler());
        server.createContext("/api/unpark", new ApiHandlers.UnparkHandler());
        server.createContext("/api/locate", new ApiHandlers.LocateHandler());
        server.createContext("/api/parked", new ApiHandlers.CurrentlyParkedHandler());
        server.createContext("/api/history", new ApiHandlers.HistoryHandler());
        server.createContext("/api/report", new ApiHandlers.ReportHandler());

        // Everything else is served as a static file from the "static" classpath folder
        server.createContext("/", new StaticFileHandler("static", "index.html"));

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("============================================================");
        System.out.println(" Smart Campus Parking and Vehicle Finder");
        System.out.println(" Server running at: http://localhost:" + port);
        System.out.println("============================================================");
    }
}
