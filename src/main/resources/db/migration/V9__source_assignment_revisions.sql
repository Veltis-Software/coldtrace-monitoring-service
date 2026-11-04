-- Local immutable control-plane history. No cross-service FK or telemetry rewrite.
CREATE TABLE source_assignment_revisions (
 iot_device_id BIGINT NOT NULL,
 organization_id BIGINT NOT NULL,
 configuration_version BIGINT NOT NULL,
 source_id VARCHAR(64) NOT NULL,
 profile_version INT NOT NULL,
 effective_at DATETIME(6) NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 payload TEXT NOT NULL,
 PRIMARY KEY (iot_device_id, configuration_version),
 CONSTRAINT uk_source_assignment_event UNIQUE (event_id),
 INDEX ix_source_assignment_time (organization_id, iot_device_id, effective_at)
);
