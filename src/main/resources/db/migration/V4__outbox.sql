-- ADR-0010 · Transactional Outbox (misma definición en ct_asset y ct_alert)
CREATE TABLE outbox_events (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id     CHAR(36)     NOT NULL,
  event_type   VARCHAR(100) NOT NULL,           -- nombre del tópico sin sufijo de entorno
  aggregate_id BIGINT       NOT NULL,
  organization_id BIGINT    NOT NULL,
  correlation_id VARCHAR(64) NULL,
  payload      JSON         NOT NULL,           -- sobre completo (Envelope) de contracts/asyncapi
  occurred_at  DATETIME(6)  NOT NULL,
  published_at DATETIME(6)  NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
  INDEX ix_outbox_pending (published_at, id)
);
