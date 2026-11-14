package com.campus.parking.web;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.User;
import com.campus.parking.model.VehicleType;
import com.campus.parking.service.AuthService;
import com.campus.parking.service.ParkingService;
import com.campus.parking.web.SessionManager.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All /api/* REST endpoints. Each inner class is one route; they share a
 * ParkingService / AuthService, which is where the real logic lives - these
 * handlers only translate HTTP <-> Java calls and check who is logged in.
 *
 * Public:
 *   POST /api/login                    -> { username, password }
 *   POST /api/register                 -> { username, password, fullName }   (creates a STUDENT)
 * Needs login:
 *   POST /api/logout
 *   GET  /api/me                       -> who am I (username, fullName, role)
 *   GET  /api/slots[?type=CAR]         -> all slots / free slots of a type
 *   POST /api/park                     -> { vehicleNumber, vehicleType, ownerName, ownerContact }
 *   POST /api/unpark                   -> { vehicleNumber }  (returns duration + fee)
 *   GET  /api/locate?vehicleNumber=..
 *   GET  /api/parked                   -> students: their own vehicles; admins: everything
 *   GET  /api/history?limit=20         -> same scoping as /api/parked
 *   GET  /api/report                   -> occupancy report
 *   GET  /api/rates                    -> hourly rate per vehicle type
 * Admin only:
 *   POST   /api/admin/slots            -> { slotNumber, vehicleType, location }
 *   DELETE /api/admin/slots/{id}
 *   GET    /api/admin/revenue          -> total fees collected
 */
public class ApiHandlers {

    private static final ParkingService SERVICE = new ParkingService();
    private static final AuthService AUTH = new AuthService();

    /**
     * Wraps a handler body so auth/validation/SQL errors always come back as clean
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
        } catch (AuthException e) {
            JsonUtil.sendError(exchange, e.getStatus(), e.getMessage());
        } catch (IllegalStateException e) {
            JsonUtil.sendError(exchange, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            JsonUtil.sendError(exchange, 400, e.getMessage());
        } catch (SQLException e) {
            JsonUtil.sendError(exchange, 500, "Database error: " + e.getMessage());
        } catch (Throwable e) {
            // Prefer the underlying cause's message: an ExceptionInInitializerError has none of its own,
            // but its cause says what really went wrong (e.g. "MySQL JDBC driver not found on classpath").
            Throwable root = e.getCause() != null ? e.getCause() : e;
            String detail = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
            JsonUtil.sendError(exchange, 500, "Unexpected error: " + detail);
        }
    }

    @FunctionalInterface
    private interface HandlerBody {
        void run() throws Exception;
    }

    /** null for an admin (no restriction), the student's own id otherwise. */
    private static Integer scopeOf(Session s) {
        return s.isAdmin() ? null : s.userId();
    }

    private static JsonUtil.JsonObject userJson(Session s) {
        return new JsonUtil.JsonObject()
                .put("userId", s.userId())
                .put("username", s.username())
                .put("fullName", s.fullName())
                .put("role", s.role().name());
    }

    // =====================================================================
    // Auth
    // =====================================================================

    public static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> body = JsonUtil.readBody(exchange);
                User user = AUTH.login(body.getOrDefault("username", ""), body.getOrDefault("password", ""));
                Session session = SessionManager.login(exchange, user);
                JsonUtil.sendJson(exchange, 200, userJson(session));
            });
        }
    }

    public static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                Map<String, String> body = JsonUtil.readBody(exchange);
                User user = AUTH.registerStudent(
                        body.getOrDefault("username", ""),
                        body.getOrDefault("password", ""),
                        body.getOrDefault("fullName", ""));
                // Log the new student straight in.
                Session session = SessionManager.login(exchange, user);
                JsonUtil.sendJson(exchange, 201, userJson(session));
            });
        }
    }

    public static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                SessionManager.logout(exchange);
                JsonUtil.sendJson(exchange, 200, new JsonUtil.JsonObject().put("ok", true));
            });
        }
    }

    public static class MeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> JsonUtil.sendJson(exchange, 200, userJson(SessionManager.requireLogin(exchange))));
        }
    }

    // =====================================================================
    // Parking
    // =====================================================================

    public static class SlotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                SessionManager.requireLogin(exchange);
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
                Session session = SessionManager.requireLogin(exchange);
                Map<String, String> body = JsonUtil.readBody(exchange);
                String vehicleNumber = required(body, "vehicleNumber").toUpperCase();
                VehicleType type = VehicleType.fromString(required(body, "vehicleType"));
                String ownerName = body.getOrDefault("ownerName", "").isBlank() ? session.fullName() : body.get("ownerName");
                String ownerContact = body.getOrDefault("ownerContact", "");

                ParkingRecord record = SERVICE.parkVehicle(vehicleNumber, type, ownerName, ownerContact, scopeOf(session));
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
                Session session = SessionManager.requireLogin(exchange);
                Map<String, String> body = JsonUtil.readBody(exchange);
                String vehicleNumber = required(body, "vehicleNumber").toUpperCase();
                ParkingRecord record = SERVICE.unparkVehicle(vehicleNumber, scopeOf(session));
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
                Session session = SessionManager.requireLogin(exchange);
                Map<String, String> q = JsonUtil.parseQuery(exchange.getRequestURI().getQuery());
                String vehicleNumber = q.getOrDefault("vehicleNumber", "").trim().toUpperCase();
                if (vehicleNumber.isBlank()) {
                    JsonUtil.sendError(exchange, 400, "vehicleNumber query parameter is required");
                    return;
                }
                Optional<ParkingRecord> found = SERVICE.locateVehicle(vehicleNumber, scopeOf(session));
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
                Session session = SessionManager.requireLogin(exchange);
                List<ParkingRecord> list = SERVICE.listCurrentlyParked(scopeOf(session));
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
                Session session = SessionManager.requireLogin(exchange);
                Map<String, String> q = JsonUtil.parseQuery(exchange.getRequestURI().getQuery());
                int limit = 20;
                try {
                    limit = Integer.parseInt(q.getOrDefault("limit", "20"));
                } catch (NumberFormatException ignored) {
                }
                limit = Math.max(1, Math.min(limit, 200));
                JsonUtil.sendJson(exchange, 200, JsonUtil.recordsToJson(SERVICE.listHistory(limit, scopeOf(session))));
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
                SessionManager.requireLogin(exchange);
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

    public static class RatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                SessionManager.requireLogin(exchange);
                JsonUtil.JsonObject out = new JsonUtil.JsonObject();
                for (var entry : SERVICE.listRates().entrySet()) {
                    out.put(entry.getKey().name(), entry.getValue());
                }
                JsonUtil.sendJson(exchange, 200, out);
            });
        }
    }

    // =====================================================================
    // Admin only
    // =====================================================================

    /** POST /api/admin/slots adds a slot; DELETE /api/admin/slots/{id} removes one. */
    public static class AdminSlotsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase();
            safely(exchange, () -> {
                SessionManager.requireAdmin(exchange);
                if (method.equals("POST")) {
                    Map<String, String> body = JsonUtil.readBody(exchange);
                    ParkingSlot slot = SERVICE.addSlot(
                            required(body, "slotNumber"),
                            VehicleType.fromString(required(body, "vehicleType")),
                            required(body, "location"));
                    JsonUtil.sendJson(exchange, 201, JsonUtil.toJson(slot));
                } else if (method.equals("DELETE")) {
                    String path = exchange.getRequestURI().getPath();
                    String idPart = path.substring(path.lastIndexOf('/') + 1);
                    int slotId;
                    try {
                        slotId = Integer.parseInt(idPart);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Use DELETE /api/admin/slots/{id}");
                    }
                    SERVICE.deleteSlot(slotId);
                    JsonUtil.sendJson(exchange, 200, new JsonUtil.JsonObject().put("deleted", slotId));
                } else {
                    JsonUtil.sendError(exchange, 405, "Use POST or DELETE");
                }
            });
        }
    }

    public static class RevenueHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                SessionManager.requireAdmin(exchange);
                JsonUtil.sendJson(exchange, 200, new JsonUtil.JsonObject().put("totalRevenue", SERVICE.totalRevenue()));
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
