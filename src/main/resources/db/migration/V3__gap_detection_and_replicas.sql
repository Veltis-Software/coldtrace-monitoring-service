-- ADR-0007 / ADR-0011 · Detección de brechas, réplicas locales y proyección del estado actual
CREATE TABLE monitored_gateways (
  gateway_id        BIGINT       NOT NULL PRIMARY KEY,
  organization_id   BIGINT       NOT NULL,
  uuid              VARCHAR(36)  NOT NULL,
  api_key_hash      VARCHAR(100) NOT NULL,
  last_heartbeat_at DATETIME(6)  NULL,
  pending_readings  INT          NULL,
  updated_at        DATETIME(6)  NOT NULL,
  CONSTRAINT uk_monitored_gateways_uuid UNIQUE (uuid)
);

CREATE TABLE device_replica (
  iot_device_id             BIGINT      NOT NULL PRIMARY KEY,
  organization_id           BIGINT      NOT NULL,
  uuid                      VARCHAR(36) NOT NULL,
  asset_id                  BIGINT      NULL,
  gateway_id                BIGINT      NULL,
  location_id               BIGINT      NULL,
  reading_frequency_seconds INT         NOT NULL,
  updated_at                DATETIME(6) NOT NULL,
  CONSTRAINT uk_device_replica_uuid UNIQUE (uuid)
);

CREATE TABLE safe_range_replica (
  asset_id                BIGINT      NOT NULL PRIMARY KEY,
  organization_id         BIGINT      NOT NULL,
  minimum_temperature     DOUBLE      NOT NULL,
  maximum_temperature     DOUBLE      NOT NULL,
  minimum_humidity        DOUBLE      NULL,
  maximum_humidity        DOUBLE      NULL,
  alert_threshold_minutes INT         NOT NULL,
  settings_version        BIGINT      NOT NULL,
  updated_at              DATETIME(6) NOT NULL
);

CREATE TABLE asset_current_state (
  asset_id          BIGINT      NOT NULL PRIMARY KEY,
  organization_id   BIGINT      NOT NULL,
  location_id       BIGINT      NULL,
  status            VARCHAR(20) NOT NULL,          -- NORMAL | OUT_OF_RANGE | NO_DATA
  last_reading_id   BIGINT      NULL,
  last_temperature  DOUBLE      NULL,
  last_humidity     DOUBLE      NULL,
  last_recorded_at  DATETIME(6) NULL,
  gap_opened_at     DATETIME(6) NULL,
  updated_at        DATETIME(6) NOT NULL,
  INDEX ix_asset_current_state_org_status (organization_id, status),
  INDEX ix_asset_current_state_org_location (organization_id, location_id)
);
