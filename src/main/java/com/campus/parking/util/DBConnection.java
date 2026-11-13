package com.campus.parking.util;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Central place that knows how to open a JDBC connection to MySQL.
 *
 * Settings are read from src/main/resources/db.properties so the
 * URL/username/password are not hard-coded, and can be overridden with
 * environment variables (DB_URL, DB_USER, DB_PASSWORD) for flexibility
 * when running on a different machine.
 */
public final class DBConnection {

    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/campus_parking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "";

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = DBConnection.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (IOException e) {
            System.err.println("Could not load db.properties, falling back to defaults: " + e.getMessage());
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL JDBC driver not found on classpath", e);
        }
    }

    private DBConnection() {
    }

    private static String url() {
        String fromEnv = System.getenv("DB_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return PROPS.getProperty("db.url", DEFAULT_URL);
    }

    private static String user() {
        String fromEnv = System.getenv("DB_USER");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return PROPS.getProperty("db.user", DEFAULT_USER);
    }

    private static String password() {
        String fromEnv = System.getenv("DB_PASSWORD");
        if (fromEnv != null) return fromEnv;
        return PROPS.getProperty("db.password", DEFAULT_PASSWORD);
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url(), user(), password());
    }
}
