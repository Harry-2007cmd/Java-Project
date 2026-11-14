package com.campus.parking.web;

/**
 * Thrown when a request is not allowed: 401 = not logged in, 403 = logged in
 * but not permitted (e.g. a student calling an admin-only endpoint).
 * ApiHandlers.safely() turns it into a clean JSON error with that status.
 */
public class AuthException extends RuntimeException {
    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
