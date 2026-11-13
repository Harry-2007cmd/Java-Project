package com.campus.parking.web;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.VehicleType;
import com.campus.parking.service.ParkingService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All /api/* REST endpoints. Each inner class is one route; they all share
 * a single ParkingService instance, which is where the real logic lives -
 * these handlers only translate HTTP <-> Java calls.
 *
 * Routes:
 *   GET  /api/slots              -> all slots
 *   GET  /api/slots?type=CAR     -> only free CAR slots
 *   POST /api/park               -> { vehicleNumber, vehicleType, ownerName, ownerContact }
 *   POST /api/unpark             -> { vehicleNumber }
 *   GET  /api/locate?vehicleNumber=KA01AB1234
 *   GET  /api/parked             -> everything currently parked
 *   GET  /api/history?limit=20
 *   GET  /api/report             -> occupancy report
 */
public class ApiHandlers {

    private static final ParkingService SERVICE = new ParkingService();

    /**
     * Wraps a handler body so SQL/validation errors always come back as clean
     * JSON instead of a stack trace or a hung connection.
     *
     * Catches Throwable, not just Exception: if the MySQL driver jar is
     * missing or the database is unreachable, DBConnection's static
     * initializer throws an ExceptionInInitializerError (an Error, not an
     * Exception). Left uncaught, that escapes HttpServer's executor thread
     * silently and the client is left waiting forever with no response -
     * catching Throwable here guarantees every request gets an HTTP reply.
     */
    private static void safely(HttpExchange exchange, HandlerBody body) throws IOException {
        try {
            body.run();
        } catch (IllegalStateException e) {
            JsonUtil.sendError(exchange, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(exchange, 400, e.getMessage());
        } catch (SQLException e) {
            JsonUtil.sendError(exchange, 500, "Database error: " + e.getMessage());
        } catch (Throwable e) {
            JsonUtil.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface HandlerBody {
        void run() throws Exception;
    }

    public static class SlotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> q = JsonUtil.parseQuery(exchange.getRequestURI().getQuery());
                String typeParam = q.get("type");
                if (typeParam != null && !typeParam.isBlank()) {
                    VehicleType type = VehicleType.fromString(typeParam);
                    JsonUtil.sendJson(exchange, 200, JsonUtil.slotsToJson(SERVICE.listAvailableSlots(type)));
                } else {
                    JsonUtil.sendJson(exchange, 200, JsonUtil.slotsToJson(SERVICE.listAllSlots()));
                }
            });
        }
    }

    public static class ParkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> body = JsonUtil.readBody(exchange);
                String vehicleNumber = required(body, "vehicleNumber").toUpperCase();
                VehicleType type = VehicleType.fromString(required(body, "vehicleType"));
                String ownerName = body.getOrDefault("ownerName", "Unknown");
                String ownerContact = body.getOrDefault("ownerContact", "");

                ParkingRecord record = SERVICE.parkVehicle(vehicleNumber, type, ownerName, ownerContact);
                JsonUtil.sendJson(exchange, 201, JsonUtil.toJson(record));
            });
        }
    }

    public static class UnparkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> body = JsonUtil.readBody(exchange);
                String vehicleNumber = required(body, "vehicleNumber").toUpperCase();
                ParkingRecord record = SERVICE.unparkVehicle(vehicleNumber);
                JsonUtil.sendJson(exchange, 200, JsonUtil.toJson(record));
            });
        }
    }

    public static class LocateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> q = JsonUtil.parseQuery(exchange.getRequestURI().getQuery());
                String vehicleNumber = q.getOrDefault("vehicleNumber", "").toUpperCase();
                if (vehicleNumber.isBlank()) {
                    JsonUtil.sendError(exchange, 400, "vehicleNumber query parameter is required");
                    return;
                }
                Optional<ParkingRecord> found = SERVICE.locateVehicle(vehicleNumber);
                if (found.isPresent()) {
                    JsonUtil.sendJson(exchange, 200, JsonUtil.toJson(found.get()));
                } else {
                    JsonUtil.sendError(exchange, 404, "Vehicle " + vehicleNumber + " is not currently parked on campus");
                }
            });
        }
    }

    public static class CurrentlyParkedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                List<ParkingRecord> list = SERVICE.listCurrentlyParked();
                JsonUtil.sendJson(exchange, 200, JsonUtil.recordsToJson(list));
            });
        }
    }

    public static class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> q = JsonUtil.parseQuery(exchange.getRequestURI().getQuery());
                int limit = 20;
                try {
                    limit = Integer.parseInt(q.getOrDefault("limit", "20"));
                } catch (NumberFormatException ignored) {
                }
                JsonUtil.sendJson(exchange, 200, JsonUtil.recordsToJson(SERVICE.listHistory(limit)));
            });
        }
    }

    public static class ReportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                var report = SERVICE.occupancyReport();
                JsonUtil.JsonObject out = new JsonUtil.JsonObject();
                int totalSlots = 0, totalOccupied = 0;
                for (var entry : report.entrySet()) {
                    var stat = entry.getValue();
                    JsonUtil.JsonObject row = new JsonUtil.JsonObject()
                            .put("totalSlots", stat.totalSlots)
                            .put("occupiedSlots", stat.occupiedSlots)
                            .put("availableSlots", stat.availableSlots())
                            .put("occupancyPercent", Math.round(stat.occupancyPercent() * 10.0) / 10.0);
                    out.put(entry.getKey().name(), row);
                    totalSlots += stat.totalSlots;
                    totalOccupied += stat.occupiedSlots;
                }
                JsonUtil.JsonObject overall = new JsonUtil.JsonObject()
                        .put("totalSlots", totalSlots)
                        .put("occupiedSlots", totalOccupied)
                        .put("availableSlots", totalSlots - totalOccupied)
                        .put("occupancyPercent", totalSlots == 0 ? 0.0 : Math.round((totalOccupied * 1000.0) / totalSlots) / 10.0);
                out.put("OVERALL", overall);
                JsonUtil.sendJson(exchange, 200, out);
            });
        }
    }

    private static String required(Map<String, String> body, String field) {
        String value = body.getOrDefault(field, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        return value;
    }
}
