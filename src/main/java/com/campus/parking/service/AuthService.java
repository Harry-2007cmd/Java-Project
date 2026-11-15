package com.campus.parking.service;

import com.campus.parking.dao.UserDAO;
import com.campus.parking.model.Role;
import com.campus.parking.model.User;
import com.campus.parking.util.AuthUtil;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Login and self-service sign-up (student or faculty), on top of UserDAO and the PBKDF2 hashing in AuthUtil. */
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.]{3,30}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern ROLL_NO = Pattern.compile("[A-Za-z0-9/_\\-]{3,30}");

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

    /**
     * Self-service sign-up for a student or a faculty member. The role comes from the sign-up form,
     * but ADMIN is never accepted here - admins are created directly in the database.
     * Students must give a roll number; faculty don't have one (any value sent for them is ignored).
     */
    public User register(String username, String password, String fullName, String email, String roleName, String rollNo)
            throws SQLException {
        Role role = parseSignUpRole(roleName);

        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (fullName.trim().length() > 100) {
            throw new IllegalArgumentException("Full name is too long (100 characters at most)");
        }

        String cleanEmail = email == null ? "" : email.trim().toLowerCase();
        if (cleanEmail.isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (cleanEmail.length() > 100 || !EMAIL.matcher(cleanEmail).matches()) {
            throw new IllegalArgumentException("Enter a valid email address");
        }

        String cleanRollNo = null;
        if (role == Role.STUDENT) {
            String candidate = rollNo == null ? "" : rollNo.trim().toUpperCase();
            if (candidate.isEmpty()) {
                throw new IllegalArgumentException("Roll number is required for students");
            }
            if (!ROLL_NO.matcher(candidate).matches()) {
                throw new IllegalArgumentException("Roll number must be 3-30 characters: letters, digits, '-', '/' or '_'");
            }
            cleanRollNo = candidate;
        }

        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 3-30 characters: letters, digits, '_' or '.'");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (password.length() > 100) {
            throw new IllegalArgumentException("Password is too long (100 characters at most)");
        }

        String salt = AuthUtil.newSalt();
        return userDAO.insert(username.trim(), AuthUtil.hash(password, salt), salt, fullName.trim(), role, cleanEmail, cleanRollNo);
    }

    private static Role parseSignUpRole(String roleName) {
        String value = roleName == null ? "" : roleName.trim().toUpperCase();
        return switch (value) {
            case "STUDENT" -> Role.STUDENT;
            case "FACULTY" -> Role.FACULTY;
            default -> throw new IllegalArgumentException("Choose whether you are a Student or Faculty");
        };
    }
}
