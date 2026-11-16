package com.campus.parking.web;

import com.campus.parking.dao.DashboardDAO;
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
 *   POST /api/register                 -> { fullName, email, username, password, role, rollNo }
 *                                         role is STUDENT or FACULTY (never ADMIN); rollNo is required for students only
 * Needs login:
 *   POST /api/logout
 *   GET  /api/me                       -> who am I (username, fullName, role, email, rollNo)
 *   GET  /api/slots[?type=CAR]         -> all slots / free slots of a type
 *   POST /api/park                     -> { vehicleNumber, vehicleType, ownerName, ownerContact }
 *   POST /api/unpark                   -> { vehicleNumber }  (returns duration + fee)
 *   GET  /api/locate?vehicleNumber=..
 *   GET  /api/parked                   -> students/faculty: their own vehicles; admins: everything
 *   GET  /api/history?limit=20         -> same scoping as /api/parked
 *   GET  /api/report                   -> occupancy report (admin only)
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

    /** null for an admin (no restriction), the student's or faculty member's own id otherwise. */
    private static Integer scopeOf(Session s) {
        return s.isAdmin() ? null : s.userId();
    }

    /** Admins only supervise and view statistics; they never park, unpark or look up a vehicle themselves. */
    private static void denyAdmin(Session s) {
        if (s.isAdmin()) {
            throw new AuthException(403, "Admins supervise only - parking actions are for students and faculty");
        }
    }

    private static JsonUtil.JsonObject userJson(Session s) {
        return new JsonUtil.JsonObject()
                .put("userId", s.userId())
                .put("username", s.username())
                .put("fullName", s.fullName())
                .put("role", s.role().name())
                .put("email", s.email())
                .put("rollNo", s.rollNo());
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
                User user = AUTH.register(
                        body.getOrDefault("username", ""),
                        body.getOrDefault("password", ""),
                        body.getOrDefault("fullName", ""),
                        body.getOrDefault("email", ""),
                        body.getOrDefault("role", ""),
                        body.getOrDefault("rollNo", ""));
                // Log the new account straight in.
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
                denyAdmin(session);
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
                denyAdmin(session);
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
                denyAdmin(session);
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
                SessionManager.requireAdmin(exchange);
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

    /** Vehicles parked longer than this are flagged as overstays on the admin dashboard. */
    private static final int OVERSTAY_MINUTES = 8 * 60;
    /** A zone at or above this share of its usable slots raises a "nearly full" alert. */
    private static final double ZONE_ALERT_PERCENT = 90.0;

    /** POST /api/admin/maintenance {slotId, outOfService: "true"|"false"} - take a free slot out of service or reopen it. */
    public static class MaintenanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use POST");
                return;
            }
            safely(exchange, () -> {
                SessionManager.requireAdmin(exchange);
                Map<String, String> body = JsonUtil.readBody(exchange);
                int slotId;
                try {
                    slotId = Integer.parseInt(required(body, "slotId"));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("slotId must be a number");
                }
                String flag = required(body, "outOfService").toLowerCase();
                if (!flag.equals("true") && !flag.equals("false")) {
                    throw new IllegalArgumentException("outOfService must be true or false");
                }
                ParkingSlot slot = SERVICE.setSlotMaintenance(slotId, flag.equals("true"));
                JsonUtil.sendJson(exchange, 200, JsonUtil.toJson(slot));
            });
        }
    }

    /** GET /api/admin/dashboard - every number the admin overview, charts and alerts need, in one call. */
    public static class DashboardHandler implements HttpHandler {
        private static final DashboardDAO DASH = new DashboardDAO();
        private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                JsonUtil.sendError(exchange, 405, "Use GET");
                return;
            }
            safely(exchange, () -> {
                SessionManager.requireAdmin(exchange);
                JsonUtil.sendJson(exchange, 200, build());
            });
        }

        private static JsonUtil.JsonObject build() throws SQLException {
            var zones = DASH.occupancyByZone();
            var types = DASH.occupancyByType();

            int total = 0, occupied = 0, oos = 0;
            for (var z : zones) {
                total += z.total();
                occupied += z.occupied();
                oos += z.outOfService();
            }
            int usable = total - oos;

            List<ParkingRecord> active = SERVICE.listCurrentlyParked(null);
            JsonUtil.JsonArray overstays = new JsonUtil.JsonArray();
            int overstayCount = 0;
            for (ParkingRecord r : active) {
                if (r.getLiveMinutes() != null && r.getLiveMinutes() >= OVERSTAY_MINUTES) {
                    overstays.add(JsonUtil.toJson(r));
                    overstayCount++;
                }
            }

            JsonUtil.JsonObject summary = new JsonUtil.JsonObject()
                    .put("totalSlots", total)
                    .put("occupied", occupied)
                    .put("available", usable - occupied)
                    .put("outOfService", oos)
                    .put("occupancyPercent", usable <= 0 ? 0.0 : Math.round(occupied * 1000.0 / usable) / 10.0)
                    .put("revenueToday", DASH.revenueLastDays(1))
                    .put("revenueWeek", DASH.revenueLastDays(7))
                    .put("revenueMonth", DASH.revenueLastDays(30))
                    .put("revenueTotal", SERVICE.totalRevenue())
                    .put("sessionsToday", DASH.sessionsStartedToday())
                    .put("overstayCount", overstayCount)
                    .put("overstayHours", OVERSTAY_MINUTES / 60);

            JsonUtil.JsonArray byZone = new JsonUtil.JsonArray();
            JsonUtil.JsonArray alerts = new JsonUtil.JsonArray();
            for (var z : zones) {
                byZone.add(occupancyJson("zone", z));
                if (z.usable() > 0 && z.percent() >= ZONE_ALERT_PERCENT) {
                    boolean full = z.occupied() >= z.usable();
                    alerts.add(alert(full ? "danger" : "warn",
                            z.name() + (full ? " is full" : " is " + Math.round(z.percent()) + "% full")
                                    + " (" + z.occupied() + "/" + z.usable() + " slots in use)"));
                }
            }
            JsonUtil.JsonArray byType = new JsonUtil.JsonArray();
            for (var t : types) {
                byType.add(occupancyJson("type", t));
                if (t.usable() > 0 && t.occupied() >= t.usable()) {
                    alerts.add(alert("danger", "No free " + t.name() + " slots left on campus"));
                }
            }
            if (overstayCount > 0) {
                alerts.add(alert("warn", overstayCount + (overstayCount == 1 ? " vehicle has" : " vehicles have")
                        + " been parked for more than " + (OVERSTAY_MINUTES / 60) + " hours"));
            }
            if (oos > 0) {
                alerts.add(alert("info", oos + (oos == 1 ? " slot is" : " slots are") + " out of service for maintenance"));
            }

            JsonUtil.JsonArray daily = new JsonUtil.JsonArray();
            for (var e : DASH.dailyRevenue(14).entrySet()) {
                daily.add(new JsonUtil.JsonObject().put("date", e.getKey().toString()).put("amount", e.getValue()));
            }
            JsonUtil.JsonArray revByType = new JsonUtil.JsonArray();
            for (var e : DASH.revenueByType(30).entrySet()) {
                revByType.add(new JsonUtil.JsonObject().put("type", e.getKey()).put("amount", e.getValue()));
            }
            JsonUtil.JsonArray hours = new JsonUtil.JsonArray();
            int[] byHour = DASH.arrivalsByHour(30);
            for (int h = 0; h < 24; h++) {
                hours.add(new JsonUtil.JsonObject().put("hour", h).put("count", byHour[h]));
            }
            JsonUtil.JsonArray weekdays = new JsonUtil.JsonArray();
            int[] byDay = DASH.arrivalsByWeekday(30);
            for (int d = 0; d < 7; d++) {
                weekdays.add(new JsonUtil.JsonObject().put("day", DAYS[d]).put("count", byDay[d]));
            }

            return new JsonUtil.JsonObject()
                    .put("summary", summary)
                    .put("byType", byType)
                    .put("byZone", byZone)
                    .put("revenueDaily", daily)
                    .put("revenueByType", revByType)
                    .put("peakHours", hours)
                    .put("peakWeekdays", weekdays)
                    .put("overstays", overstays)
                    .put("alerts", alerts);
        }

        private static JsonUtil.JsonObject occupancyJson(String key, DashboardDAO.Occupancy o) {
            return new JsonUtil.JsonObject()
                    .put(key, o.name())
                    .put("total", o.total())
                    .put("occupied", o.occupied())
                    .put("outOfService", o.outOfService())
                    .put("available", o.usable() - o.occupied())
                    .put("percent", o.percent());
        }

        private static JsonUtil.JsonObject alert(String level, String message) {
            return new JsonUtil.JsonObject().put("level", level).put("message", message);
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
