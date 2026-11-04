package com.acme.coldtrace.platform.monitoring.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Projects the owning Asset context's immutable revisions under the ingestion gateway lock. */
@Service
public class SourceConfigurationConsumer {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public SourceConfigurationConsumer(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional
  public void accept(JsonNode event) {
    try {
      apply(event);
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IllegalArgumentException | java.time.DateTimeException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid source configuration event", exception);
    }
  }

  private void apply(JsonNode event) {
    if (event == null || !event.isObject()) throw new IllegalArgumentException("Event required");
    if (!"source.configuration-changed".equals(event.path("eventType").asText())
        || positive(event, "version") != 1) throw new IllegalArgumentException("Unsupported event");
    String eventId = UUID.fromString(event.path("eventId").asText()).toString();
    long org = positive(event, "organizationId");
    var payload = event.path("payload");
    long device = positive(payload, "deviceId"),
        gateway = positive(payload, "gatewayId"),
        asset = positive(payload, "assetId"),
        version = positive(payload, "configurationVersion");
    if (positive(payload, "organizationId") != org)
      throw new IllegalArgumentException("Tenant mismatch");
    String uuid = UUID.fromString(payload.path("deviceUuid").asText()).toString();
    Instant effectiveAt =
        Instant.parse(payload.path("changedAt").asText()).truncatedTo(ChronoUnit.MICROS);
    var profile = payload.path("profile");
    String source = profile.path("sourceId").asText();
    long profileVersion = positive(profile, "profileVersion");
    if (!source.matches("[A-Za-z0-9_-]{1,64}")
        || profileVersion > Integer.MAX_VALUE
        || positive(profile, "schemaVersion") != 1
        || !Set.of("mqtt", "http").contains(profile.path("transport").asText())
        || !Set.of("C", "F").contains(profile.path("temperatureUnit").asText())
        || !"%RH".equals(profile.path("humidityUnit").asText()))
      throw new IllegalArgumentException("Invalid source profile");
    var fields = profile.path("fields");
    if (!fields.isObject() || fields.size() != 3)
      throw new IllegalArgumentException("Invalid fields");
    var names = new HashSet<String>();
    for (String name : List.of("temperature", "humidity", "recordedAt")) {
      var value = fields.path(name);
      if (!value.isTextual()
          || value.asText().isBlank()
          || value.asText().length() > 128
          || !names.add(value.asText()))
        throw new IllegalArgumentException("Invalid field mapping");
    }
    var gateways =
        jdbc.queryForList(
            "SELECT organization_id FROM monitored_gateways WHERE gateway_id=? FOR UPDATE",
            gateway);
    if (gateways.isEmpty()) throw conflict("Gateway is not provisioned yet");
    if (((Number) gateways.getFirst().get("organization_id")).longValue() != org)
      throw new IllegalArgumentException("Gateway ownership mismatch");
    var owners =
        jdbc.queryForList(
            "SELECT organization_id,gateway_id,asset_id,uuid FROM device_replica WHERE"
                + " iot_device_id=?",
            device);
    if (owners.isEmpty()) throw conflict("Device is not provisioned yet");
    var owner = owners.getFirst();
    if (((Number) owner.get("organization_id")).longValue() != org
        || !(owner.get("gateway_id") instanceof Number g)
        || g.longValue() != gateway
        || !(owner.get("asset_id") instanceof Number a)
        || a.longValue() != asset
        || !uuid.equalsIgnoreCase(owner.get("uuid").toString()))
      throw new IllegalArgumentException("Source association ownership mismatch");

    var duplicate =
        jdbc.queryForList(
            "SELECT payload FROM source_assignment_revisions WHERE event_id=?", eventId);
    if (!duplicate.isEmpty()) {
      if (!decode(duplicate.getFirst().get("payload").toString())
          .equals(decode(payload.toString())))
        throw conflict("Event identity was reused with different content");
      return;
    }
    var previous =
        jdbc.query(
            "SELECT configuration_version,source_id,profile_version,effective_at FROM"
                + " source_assignment_revisions WHERE iot_device_id=? ORDER BY"
                + " configuration_version DESC LIMIT 1",
            (rs, row) ->
                new com.acme.coldtrace.platform.monitoring.domain.SourceAssignment(
                    rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant()),
            device);
    long current = previous.isEmpty() ? 0 : previous.getFirst().configurationVersion();
    if (version != current + 1)
      throw conflict("Configuration revisions must be delivered in order");
    if (!previous.isEmpty() && effectiveAt.isBefore(previous.getFirst().effectiveAt()))
      throw conflict("Configuration time cannot move backwards");
    jdbc.update(
        "INSERT INTO"
            + " source_assignment_revisions(iot_device_id,organization_id,configuration_version,source_id,profile_version,effective_at,event_id,payload)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        device,
        org,
        version,
        source,
        profileVersion,
        Timestamp.from(effectiveAt),
        eventId,
        payload.toString());
  }

  private static long positive(JsonNode object, String field) {
    var value = object.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
      throw new IllegalArgumentException("Invalid " + field);
    return value.longValue();
  }

  private JsonNode decode(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Invalid stored source event", exception);
    }
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
