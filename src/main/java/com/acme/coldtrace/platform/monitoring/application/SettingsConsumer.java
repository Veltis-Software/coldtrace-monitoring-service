package com.acme.coldtrace.platform.monitoring.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsConsumer {
  private final JdbcTemplate jdbc;

  public SettingsConsumer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void accept(JsonNode event) {
    UUID id = UUID.fromString(event.path("eventId").asText());
    long org = event.path("organizationId").asLong();
    var payload = event.path("payload");
    long asset = payload.path("assetId").asLong(),
        version = payload.path("settingsVersion").asLong(-1);
    double min = payload.path("minimumTemperature").asDouble(Double.NaN),
        max = payload.path("maximumTemperature").asDouble(Double.NaN);
    int tolerance = payload.path("alertThresholdMinutes").asInt(-1);
    Double minHum =
        payload.hasNonNull("minimumHumidity") ? payload.get("minimumHumidity").doubleValue() : null;
    Double maxHum =
        payload.hasNonNull("maximumHumidity") ? payload.get("maximumHumidity").doubleValue() : null;
    if (!"asset.settings-changed".equals(event.path("eventType").asText())
        || event.path("version").asInt() != 1
        || org <= 0
        || asset <= 0
        || version < 0
        || tolerance < 0
        || !Double.isFinite(min)
        || !Double.isFinite(max)
        || min > max
        || (minHum != null && (!Double.isFinite(minHum) || minHum < 0 || minHum > 100))
        || (maxHum != null && (!Double.isFinite(maxHum) || maxHum < 0 || maxHum > 100))
        || (minHum != null && maxHum != null && minHum > maxHum))
      throw new IllegalArgumentException("Invalid settings event");
    // Lock gateways first to retain the same ordering as ingestion.
    jdbc.queryForList(
        "SELECT gateway_id FROM monitored_gateways WHERE organization_id=? ORDER BY gateway_id FOR"
            + " UPDATE",
        org);
    var rows =
        jdbc.queryForList(
            "SELECT organization_id,settings_version FROM safe_range_replica WHERE asset_id=? FOR"
                + " UPDATE",
            asset);
    if (!rows.isEmpty() && ((Number) rows.get(0).get("organization_id")).longValue() != org)
      throw new IllegalArgumentException("Asset ownership mismatch");
    if (jdbc.update(
            "INSERT IGNORE INTO processed_events(event_id,processed_at) VALUES (?,?)",
            id.toString(),
            Timestamp.from(Instant.now()))
        == 0) return;
    if (!rows.isEmpty() && ((Number) rows.get(0).get("settings_version")).longValue() >= version)
      return;
    jdbc.update(
        "INSERT INTO"
            + " safe_range_replica(asset_id,organization_id,minimum_temperature,maximum_temperature,minimum_humidity,maximum_humidity,alert_threshold_minutes,settings_version,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " minimum_temperature=VALUES(minimum_temperature),maximum_temperature=VALUES(maximum_temperature),minimum_humidity=VALUES(minimum_humidity),maximum_humidity=VALUES(maximum_humidity),alert_threshold_minutes=VALUES(alert_threshold_minutes),settings_version=VALUES(settings_version),updated_at=VALUES(updated_at)",
        asset,
        org,
        min,
        max,
        minHum,
        maxHum,
        tolerance,
        version,
        Timestamp.from(Instant.now()));
    // Re-evaluate the duration under the new rule, without emitting before another reading arrives.
    jdbc.update(
        "DELETE FROM threshold_windows WHERE iot_device_id IN (SELECT iot_device_id FROM"
            + " device_replica WHERE asset_id=? AND organization_id=?)",
        asset,
        org);
  }
}
