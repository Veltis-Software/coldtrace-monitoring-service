CREATE TABLE threshold_windows (
  iot_device_id BIGINT NOT NULL PRIMARY KEY,
  first_breached_at DATETIME(6) NULL,
  last_evaluated_at DATETIME(6) NOT NULL,
  notified BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE processed_events (
  event_id CHAR(36) NOT NULL PRIMARY KEY,
  processed_at DATETIME(6) NOT NULL
);
