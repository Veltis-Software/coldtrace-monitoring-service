-- Brownfield SensorReadingPersistenceEntity, isolated in ct_monitoring. No cross-schema FK.
CREATE TABLE sensor_readings (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 organization_id BIGINT NOT NULL,
 asset_id BIGINT NOT NULL,
 iot_device_id BIGINT NOT NULL,
 gateway_id BIGINT NOT NULL,
 location_id BIGINT NOT NULL,
 temperature DOUBLE NULL,
 humidity DOUBLE NULL,
 out_of_range BOOLEAN NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 motion_detected BOOLEAN NULL,
 image_captured BOOLEAN NULL,
 battery_level INT NULL,
 signal_strength INT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL
);
