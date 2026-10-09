CREATE TABLE telemetry_batches (
 gateway_id BIGINT NOT NULL,
 idempotency_key CHAR(36) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(gateway_id,idempotency_key)
);
