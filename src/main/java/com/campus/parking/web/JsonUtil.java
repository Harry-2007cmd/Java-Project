package com.campus.parking.web;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny hand-written JSON support, used instead of a third-party JSON
 * library so the project has exactly one external dependency (the MySQL
 * driver). It is deliberately small: just enough object/array/field
 * building and parsing to serve this app's own REST API, not a
 * general-purpose JSON library.
 */
final class JsonUtil {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JsonUtil() {
    }

    // ---------------------------------------------------------------
    // Building JSON (writing)
    // ---------------------------------------------------------------

    /** A tiny ordered JSON object builder: put(...) calls chain, toString() renders it. */
    static final class JsonObject {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        JsonObject put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : fields.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(quote(entry.getKey())).append(":").append(render(entry.getValue()));
            }
            return sb.append("}").toString();
        }
    }

    /** A tiny JSON array builder made of JsonObject entries. */
    static final class JsonArray {
        private final List<Object> items = new java.util.ArrayList<>();

        JsonArray add(Object value) {
            items.add(value);
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(render(items.get(i)));
            }
            return sb.append("]").toString();
        }
    }

    private static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof JsonObject || value instanceof JsonArray) return value.toString();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return quote(value.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }

    // ---------------------------------------------------------------
    // Model -> JSON
    // ---------------------------------------------------------------

    static JsonObject toJson(ParkingSlot s) {
        return new JsonObject()
                .put("slotId", s.getSlotId())
                .put("slotNumber", s.getSlotNumber())
                .put("vehicleType", s.getVehicleType().name())
                .put("location", s.getLocation())
                .put("occupied", s.isOccupied())
                .put("outOfService", s.isOutOfService());
    }

    static JsonObject toJson(ParkingRecord r) {
        return new JsonObject()
                .put("recordId", r.getRecordId())
                .put("vehicleNumber", r.getVehicleNumber())
                .put("ownerName", r.getOwnerName())
                .put("vehicleType", r.getVehicleType())
                .put("slotId", r.getSlotId())
                .put("liveMinutes", r.isActive() ? r.getLiveMinutes() : null)
                .put("slotNumber", r.getSlotNumber())
                .put("location", r.getLocation())
                .put("entryTime", r.getEntryTime() != null ? r.getEntryTime().format(FMT) : null)
                .put("exitTime", r.getExitTime() != null ? r.getExitTime().format(FMT) : null)
                .put("durationMinutes", r.getDurationMinutes())
                .put("feeAmount", r.getFeeAmount())
                .put("active", r.isActive());
    }

    static JsonArray slotsToJson(List<ParkingSlot> slots) {
        JsonArray arr = new JsonArray();
        for (ParkingSlot s : slots) arr.add(toJson(s));
        return arr;
    }

    static JsonArray recordsToJson(List<ParkingRecord> records) {
        JsonArray arr = new JsonArray();
        for (ParkingRecord r : records) arr.add(toJson(r));
        return arr;
    }

    // ---------------------------------------------------------------
    // HTTP response helpers
    // ---------------------------------------------------------------

    static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, new JsonObject().put("error", message));
    }

    // ---------------------------------------------------------------
    // Reading JSON request bodies (only needs flat string fields here)
    // ---------------------------------------------------------------

    /** Minimal parser for flat {"key":"value", "key2":"value2"} objects - all this app's POST bodies use. */
    static Map<String, String> readBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        Map<String, String> map = new LinkedHashMap<>();
        if (raw.isEmpty() || raw.equals("{}")) return map;
        // strip outer braces
        String inner = raw.substring(raw.indexOf('{') + 1, raw.lastIndexOf('}'));
        int i = 0, n = inner.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(inner.charAt(i))) i++;
            if (i >= n || inner.charAt(i) != '"') break;
            StringBuilder key = new StringBuilder();
            i++;
            while (i < n && inner.charAt(i) != '"') key.append(inner.charAt(i++));
            i++; // closing quote
            while (i < n && (inner.charAt(i) == ' ' || inner.charAt(i) == ':')) i++;
            String value;
            if (i < n && inner.charAt(i) == '"') {
                StringBuilder val = new StringBuilder();
                i++;
                while (i < n && inner.charAt(i) != '"') {
                    char c = inner.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        i++;
                        val.append(unescape(inner.charAt(i)));
                    } else {
                        val.append(c);
                    }
                    i++;
                }
                i++; // closing quote
                value = val.toString();
            } else {
                StringBuilder val = new StringBuilder();
                while (i < n && inner.charAt(i) != ',' ) val.append(inner.charAt(i++));
                value = val.toString().trim();
                if (value.equals("null")) value = "";
            }
            map.put(key.toString(), value);
            while (i < n && (inner.charAt(i) == ',' || Character.isWhitespace(inner.charAt(i)))) i++;
        }
        return map;
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            default -> c;
        };
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }
}
