-- Nullable context preserves historical readings and legacy gateway batches.
ALTER TABLE sensor_readings ADD COLUMN source_id VARCHAR(64) NULL;
ALTER TABLE sensor_readings ADD COLUMN profile_version INT NULL;
ALTER TABLE sensor_readings ADD COLUMN schema_version INT NULL;
ALTER TABLE sensor_readings ADD COLUMN temperature_unit VARCHAR(8) NULL;
ALTER TABLE sensor_readings ADD COLUMN humidity_unit VARCHAR(8) NULL;
