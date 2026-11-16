-- Upgrade an EXISTING campus_parking database for the admin dashboard without wiping data.
-- (The app also does this automatically on first use; this script is for accounts that cannot ALTER tables.)
USE campus_parking;

SET @has := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'parking_slots' AND COLUMN_NAME = 'out_of_service');
SET @ddl := IF(@has = 0,
               'ALTER TABLE parking_slots ADD COLUMN out_of_service BOOLEAN NOT NULL DEFAULT FALSE',
               'SELECT ''out_of_service already present'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
