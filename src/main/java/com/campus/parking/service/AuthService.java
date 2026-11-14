package com.campus.parking.service;

import com.campus.parking.dao.UserDAO;
import com.campus.parking.model.Role;
import com.campus.parking.model.User;
import com.campus.parking.util.AuthUtil;

import java.sql.SQLException;
import java.util.Optional;

/** Login and student sign-up, on top of UserDAO and the PBKDF2 hashing in AuthUtil. */
public class AuthService {

    private final UserDAO userDAO = new UserDAO();

    /** Returns the user if the username/password are right, otherwise throws IllegalArgumentException. */
    public User login(String username, String password) throws SQLException {
        Optional<User> found = userDAO.findByUsername(username == null ? "" : username.trim());
        // Same message for "no such user" and "wrong password" so the form doesn't reveal which usernames exist.
        if (found.isEmpty() || !AuthUtil.matches(password == null ? "" : password, found.get().getSalt(), found.get().getPasswordHash())) {
            throw new IllegalArgumentException("Wrong username or password");
        }
        return found.get();
    }

    /** Self-service sign-up. New accounts are always STUDENT - admins are created directly in the database. */
    public User registerStudent(String username, String password, String fullName) throws SQLException {
        if (username == null || !username.trim().matches("[A-Za-z0-9_.]{3,30}")) {
            throw new IllegalArgumentException("Username must be 3-30 characters: letters, digits, '_' or '.'");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        String salt = AuthUtil.newSalt();
        return userDAO.insert(username.trim(), AuthUtil.hash(password, salt), salt, fullName.trim(), Role.STUDENT);
    }
}
