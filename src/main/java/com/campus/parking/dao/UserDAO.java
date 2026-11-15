package com.campus.parking.dao;

import com.campus.parking.model.Role;
import com.campus.parking.model.User;
import com.campus.parking.util.DBConnection;
import com.campus.parking.util.SchemaMigrator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Optional;

/** Data-access for the users table. */
public class UserDAO {

    public Optional<User> findByUsername(String username) throws SQLException {
        return findOne("username", username);
    }

    public Optional<User> findByEmail(String email) throws SQLException {
        return findOne("email", email);
    }

    public Optional<User> findByRollNo(String rollNo) throws SQLException {
        return findOne("roll_no", rollNo);
    }

    // column is always one of our own constants above, never user input.
    private Optional<User> findOne(String column, String value) throws SQLException {
        SchemaMigrator.ensureUserColumns();
        String sql = "SELECT * FROM users WHERE " + column + " = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Creates an account without email/roll number (kept for older callers). */
    public User insert(String username, String passwordHash, String salt, String fullName, Role role) throws SQLException {
        return insert(username, passwordHash, salt, fullName, role, null, null);
    }

    /**
     * Creates a new account. Throws IllegalStateException if the username, email or roll number
     * is already taken (checked up front for a friendly message, and again via the database's
     * UNIQUE constraints in case two people sign up at the same moment).
     */
    public User insert(String username, String passwordHash, String salt, String fullName, Role role,
                       String email, String rollNo) throws SQLException {
        if (findByUsername(username).isPresent()) {
            throw new IllegalStateException("Username '" + username + "' is already taken");
        }
        if (email != null && findByEmail(email).isPresent()) {
            throw new IllegalStateException("An account with this email already exists");
        }
        if (rollNo != null && findByRollNo(rollNo).isPresent()) {
            throw new IllegalStateException("An account with this roll number already exists");
        }
        String sql = "INSERT INTO users (username, password_hash, salt, full_name, role, email, roll_no) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, salt);
            ps.setString(4, fullName);
            ps.setString(5, role.name());
            ps.setString(6, email);     // setString(.., null) stores NULL, which UNIQUE allows many of
            ps.setString(7, rollNo);
            ps.executeUpdate();
            User user = new User(0, username, passwordHash, salt, fullName, role, email, rollNo);
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    user.setUserId(keys.getInt(1));
                }
            }
            return user;
        } catch (SQLIntegrityConstraintViolationException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase();
            if (msg.contains("email")) throw new IllegalStateException("An account with this email already exists");
            if (msg.contains("roll_no")) throw new IllegalStateException("An account with this roll number already exists");
            throw new IllegalStateException("Username '" + username + "' is already taken");
        }
    }

    private User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getInt("user_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("salt"),
                rs.getString("full_name"),
                Role.valueOf(rs.getString("role")),
                optional(rs, "email"),
                optional(rs, "roll_no")
        );
    }

    /** Reads a column that an un-migrated database may not have yet; returns null instead of failing the login. */
    private static String optional(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }
}
