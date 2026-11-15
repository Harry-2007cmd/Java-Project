package com.campus.parking.model;

/**
 * What a logged-in user is allowed to do. Students and faculty use parking and only ever
 * see their own vehicles; admins also manage slots and see everything.
 */
public enum Role {
    STUDENT, FACULTY, ADMIN
}
