-- Flyway 12.0 builds SQLite table drops as a multi-statement command when
-- foreign keys are enabled. Xerial executes only the leading PRAGMA in that
-- command, so test clean() can report success without dropping any table.
-- Production never runs clean; this callback is test-only and runs on the
-- exact Flyway connection before each isolated test database reset.
PRAGMA foreign_keys=OFF;
