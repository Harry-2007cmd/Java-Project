package com.campus.parking.model;

/** A student, faculty or admin account. The password hash and salt never leave the DAO/auth layer. */
public class User {
    private int userId;
    private String username;
    private String passwordHash;
    private String salt;
    private String fullName;
    private Role role;
    private String email;    // null for accounts created before email was collected (e.g. the demo accounts)
    private String rollNo;   // students only; null for faculty and admins

    public User() {
    }

    public User(int userId, String username, String passwordHash, String salt, String fullName, Role role) {
        this(userId, username, passwordHash, salt, fullName, role, null, null);
    }

    public User(int userId, String username, String passwordHash, String salt, String fullName, Role role,
                String email, String rollNo) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.fullName = fullName;
        this.role = role;
        this.email = email;
        this.rollNo = rollNo;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRollNo() {
        return rollNo;
    }

    public void setRollNo(String rollNo) {
        this.rollNo = rollNo;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
