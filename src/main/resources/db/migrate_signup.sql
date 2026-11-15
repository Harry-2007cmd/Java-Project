-- ============================================================
-- Upgrade an EXISTING campus_parking database for the new sign-up
-- (email, roll number, student/faculty roles) WITHOUT losing data.
--
-- You normally don't need to run this: the app applies the same changes
-- automatically the first time it starts. Run it by hand only if the
-- app printed "[migration] Could not upgrade the users table".
-- Run it once; running it twice will report "Duplicate column name".
-- ============================================================
USE campus_parking;

ALTER TABLE users
    ADD COLUMN email   VARCHAR(100) NULL UNIQUE,
    ADD COLUMN roll_no VARCHAR(30)  NULL UNIQUE;

ALTER TABLE users
    MODIFY COLUMN role ENUM('STUDENT', 'ADMIN', 'FACULTY') NOT NULL DEFAULT 'STUDENT';
