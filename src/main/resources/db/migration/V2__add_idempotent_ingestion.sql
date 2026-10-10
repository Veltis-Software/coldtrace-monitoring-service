-- ADR-0006 · Iteración 1 (Disponibilidad) · Ingesta idempotente
ALTER TABLE sensor_readings
  ADD COLUMN sequence_number BIGINT NOT NULL DEFAULT 0 AFTER iot_device_id;

-- Las filas históricas (sequence_number = 0) se diferencian por recorded_at; si existieran
-- duplicados históricos exactos, depurarlos antes de crear la restricción.
ALTER TABLE sensor_readings
  ADD CONSTRAINT uk_sensor_readings_device_time_seq UNIQUE (iot_device_id, recorded_at, sequence_number);

CREATE INDEX ix_sensor_readings_asset_time ON sensor_readings (asset_id, recorded_at);
