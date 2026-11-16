package com.campus.parking.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Upgrades an existing database to the current sign-up schema without wiping it:
 * adds users.email, users.roll_no and the FACULTY role if they are missing.
 *
 * Every step first checks whether it is already done, so it is safe to run on every start
 * (and a no-op on a database created from the latest schema.sql). It runs once per JVM,
 * the first time the users table is touched, so the web server and the console app both get it.
 * If the upgrade cannot run (e.g. the DB account may not ALTER tables) the app keeps working
 * with the old columns and prints a warning telling you to re-run schema.sql.
 */
public final class SchemaMigrator {

    private static boolean done = false;
    private static boolean slotsDone = false;

    private SchemaMigrator() {
    }

    public static synchronized void ensureUserColumns() {
        if (done) {
            return;
        }
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            if (!columnExists(con, "email")) {
                st.executeUpdate("ALTER TABLE users ADD COLUMN email VARCHAR(100) NULL UNIQUE");
                System.out.println("[migration] added users.email");
            }
            if (!columnExists(con, "roll_no")) {
                st.executeUpdate("ALTER TABLE users ADD COLUMN roll_no VARCHAR(30) NULL UNIQUE");
                System.out.println("[migration] added users.roll_no");
            }
            String roleType = columnType(con, "role");
            if (roleType != null && !roleType.toUpperCase().contains("'FACULTY'")) {
                // FACULTY is appended at the end so existing rows keep their meaning.
                st.executeUpdate("ALTER TABLE users MODIFY COLUMN role ENUM('STUDENT','ADMIN','FACULTY') NOT NULL DEFAULT 'STUDENT'");
                System.out.println("[migration] added FACULTY to users.role");
            }
            done = true;
        } catch (SQLException e) {
            System.err.println("[migration] Could not upgrade the users table automatically: " + e.getMessage()
                    + "\n[migration] Run src/main/resources/db/schema.sql (or migrate_signup.sql) once, then restart.");
        }
    }

    /** Adds parking_slots.out_of_service (the admin "maintenance" switch) if it is missing. Safe to call repeatedly. */
    public static synchronized void ensureSlotColumns() {
        if (slotsDone) {
            return;
        }
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            if (columnType(con, "parking_slots", "out_of_service") == null) {
                st.executeUpdate("ALTER TABLE parking_slots ADD COLUMN out_of_service BOOLEAN NOT NULL DEFAULT FALSE");
                System.out.println("[migration] added parking_slots.out_of_service");
            }
            slotsDone = true;
        } catch (SQLException e) {
            System.err.println("[migration] Could not add parking_slots.out_of_service automatically: " + e.getMessage()
                    + "\n[migration] Run src/main/resources/db/migrate_admin.sql once, then restart.");
        }
    }

    private static boolean columnExists(Connection con, String column) throws SQLException {
        return columnType(con, column) != null;
    }

    /** The column's MySQL type (e.g. "varchar(100)" or "enum('STUDENT','ADMIN')"), or null if the column does not exist. */
    private static String columnType(Connection con, String column) throws SQLException {
        return columnType(con, "users", column);
    }

    private static String columnType(Connection con, String table, String column) throws SQLException {
        String sql = "SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
