package com.campus.parking.web;

import com.campus.parking.model.Role;
import com.campus.parking.model.User;
import com.campus.parking.util.AuthUtil;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of who is logged in. Sessions live in memory, keyed by a random
 * token stored in a browser cookie (SID), so restarting the server logs
 * everyone out - fine for a mini project, and no extra tables or libraries.
 */
public final class SessionManager {

    private static final String COOKIE_NAME = "SID";
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private SessionManager() {
    }

    /** The logged-in user's identity for the lifetime of one session. */
    public record Session(int userId, String username, String fullName, Role role, String email, String rollNo) {
        public boolean isAdmin() {
            return role == Role.ADMIN;
        }
    }

    /** Creates a session for the user and tells the browser to remember its token in a cookie. */
    public static Session login(HttpExchange exchange, User user) {
        String token = AuthUtil.newToken();
        Session session = new Session(user.getUserId(), user.getUsername(), user.getFullName(), user.getRole(),
                user.getEmail(), user.getRollNo());
        SESSIONS.put(token, session);
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        return session;
    }

    public static void logout(HttpExchange exchange) {
        String token = readToken(exchange);
        if (token != null) {
            SESSIONS.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    /** The current session, or null if nobody is logged in. */
    public static Session current(HttpExchange exchange) {
        String token = readToken(exchange);
        return token == null ? null : SESSIONS.get(token);
    }

    /** Use at the top of any endpoint that needs a logged-in user. */
    public static Session requireLogin(HttpExchange exchange) {
        Session s = current(exchange);
        if (s == null) {
            throw new AuthException(401, "Please log in first");
        }
        return s;
    }

    /** Use at the top of any admin-only endpoint. */
    public static Session requireAdmin(HttpExchange exchange) {
        Session s = requireLogin(exchange);
        if (!s.isAdmin()) {
            throw new AuthException(403, "Admin access required");
        }
        return s;
    }

    private static String readToken(HttpExchange exchange) {
        var cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE_NAME) && !kv[1].isEmpty()) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
