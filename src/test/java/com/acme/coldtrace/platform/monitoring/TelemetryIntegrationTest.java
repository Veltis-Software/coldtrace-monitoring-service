package com.acme.coldtrace.platform.monitoring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.acme.coldtrace.platform.monitoring.application.*;
import com.acme.coldtrace.platform.monitoring.domain.Telemetry.*;
import com.acme.coldtrace.platform.monitoring.infrastructure.LegacyIdentityClient;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "coldtrace.pubsub.local-push-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TelemetryIntegrationTest {
  public static final UUID GATEWAY = UUID.fromString("11111111-1111-1111-1111-111111111111"),
      DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final Instant NOW = Instant.parse("2026-10-09T20:00:00Z");
  @Autowired TelemetryService service;
  @Autowired SourceGapDetector gaps;
  @Autowired SettingsConsumer settings;
  @Autowired SourceConfigurationConsumer sourceConfigurations;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired MockMvc mvc;
  @MockBean Clock clock;
  @MockBean LegacyIdentityClient identity;

  @BeforeEach
  public void seed() {
    when(clock.instant()).thenReturn(NOW);
    when(identity.organization("Bearer org-1")).thenReturn(1L);
    when(identity.organization("Bearer org-2")).thenReturn(2L);
    for (String table :
        List.of(
            "source_assignment_revisions",
            "asset_replica",
            "threshold_windows",
            "processed_events",
            "telemetry_batches",
            "outbox_events",
            "asset_current_state",
            "safe_range_replica",
            "device_replica",
            "monitored_gateways",
            "sensor_readings")) jdbc.update("DELETE FROM " + table);
    jdbc.update(
        "INSERT INTO monitored_gateways(gateway_id,organization_id,uuid,api_key_hash,updated_at)"
            + " VALUES (1,1,?,?,?)",
        GATEWAY.toString(),
        passwords.encode("local-key"),
        Timestamp.from(NOW));
    jdbc.update(
        "INSERT INTO"
            + " device_replica(iot_device_id,organization_id,uuid,asset_id,gateway_id,location_id,reading_frequency_seconds,updated_at)"
            + " VALUES (1,1,?,1,1,1,10,?)",
        DEVICE.toString(),
        Timestamp.from(NOW));
    jdbc.update(
        "INSERT INTO"
            + " safe_range_replica(asset_id,organization_id,minimum_temperature,maximum_temperature,minimum_humidity,maximum_humidity,alert_threshold_minutes,settings_version,updated_at)"
            + " VALUES (1,1,2,8,40,80,0,1,?)",
        Timestamp.from(NOW));
  }

  public Reading reading(long seq, Instant at, double temperature) {
    return new Reading(DEVICE, seq, at, temperature, 60.0, null, null);
  }

  com.fasterxml.jackson.databind.node.ObjectNode sourceConfiguration(
      long version, String source, Instant at) {
    return json.valueToTree(
        Map.of(
            "eventId",
            UUID.randomUUID().toString(),
            "eventType",
            "source.configuration-changed",
            "version",
            1,
            "organizationId",
            1,
            "payload",
            Map.of(
                "organizationId",
                1,
                "deviceId",
                1,
                "gatewayId",
                1,
                "assetId",
                1,
                "deviceUuid",
                DEVICE.toString(),
                "configurationVersion",
                version,
                "changedAt",
                at.toString(),
                "profile",
                Map.of(
                    "sourceId",
                    source,
                    "transport",
                    "mqtt",
                    "profileVersion",
                    1,
                    "schemaVersion",
                    1,
                    "temperatureUnit",
                    "C",
                    "humidityUnit",
                    "%RH",
                    "fields",
                    Map.of(
                        "temperature",
                        "temperature",
                        "humidity",
                        "humidity",
                        "recordedAt",
                        "recordedAt")))));
  }

  Reading sourced(long sequence, Instant at, String source) {
    return new Reading(DEVICE, sequence, at, 4.0, 60.0, null, null, source, 1, 1, "C", "%RH");
  }

  @Test
  void localPushAdapterDispatchesSourceEventsIdempotently() throws Exception {
    var event = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    var push =
        json.writeValueAsString(
            Map.of(
                "message",
                Map.of("data", Base64.getEncoder().encodeToString(json.writeValueAsBytes(event)))));
    for (int retry = 0; retry < 2; retry++) {
      mvc.perform(post("/internal/pubsub/events").contentType("application/json").content(push))
          .andExpect(status().isNoContent());
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isEqualTo(1);
  }

  @Test
  void sourceReplacementAcceptsBufferedOldReadingsAndRejectsRetiredSourceAtomically() {
    sourceConfigurations.accept(sourceConfiguration(1, "probe-a", NOW.minusSeconds(30)));
    sourceConfigurations.accept(sourceConfiguration(2, "probe-b", NOW.minusSeconds(10)));
    var result =
        service.ingest(
            GATEWAY,
            UUID.randomUUID(),
            "local-key",
            List.of(sourced(1, NOW.minusSeconds(20), "probe-a"), sourced(2, NOW, "probe-b")),
            "replacement");
    assertThat(result.accepted()).isEqualTo(2);
    long beforeEvents = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
    long beforeBatches = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_batches", Long.class);
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY,
                    UUID.randomUUID(),
                    "local-key",
                    List.of(sourced(3, NOW.minusSeconds(1), "probe-b"), sourced(4, NOW, "probe-a")),
                    "retired"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("effective configuration");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
        .isEqualTo(beforeEvents);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_batches", Long.class))
        .isEqualTo(beforeBatches);
  }

  @Test
  void sourceRevisionsDeduplicateAndRetryMissingPredecessors() {
    var first = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    var second = sourceConfiguration(2, "probe-b", NOW.minusSeconds(10));
    assertThatThrownBy(() -> sourceConfigurations.accept(second))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("in order");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isZero();
    sourceConfigurations.accept(first);
    sourceConfigurations.accept(second);
    sourceConfigurations.accept(first);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isEqualTo(2);
    var altered = first.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("payload").path("profile"))
        .put("sourceId", "tampered");
    assertThatThrownBy(() -> sourceConfigurations.accept(altered))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("different content");
  }

  @Test
  void aLiveEventAfterHistoryBootstrapDoesNotDuplicateTheRevision() {
    var bootstrap = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    sourceConfigurations.accept(bootstrap);
    var live = bootstrap.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) live)
        .put("eventId", UUID.randomUUID().toString());
    sourceConfigurations.accept(live);
    sourceConfigurations.accept(sourceConfiguration(2, "probe-b", NOW.minusSeconds(10)));
    sourceConfigurations.accept(live);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isEqualTo(2);
  }

  @Test
  void aDifferentEventCannotOverwriteAnExistingConfigurationRevision() {
    var first = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    sourceConfigurations.accept(first);
    var altered = sourceConfiguration(1, "probe-b", NOW.minusSeconds(30));
    assertThatThrownBy(() -> sourceConfigurations.accept(altered))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("different content");
    assertThat(jdbc.queryForObject("SELECT source_id FROM source_assignment_revisions", String.class))
        .isEqualTo("probe-a");
  }

  @Test
  void sourceAssociationMustMatchProvisionedOwnership() {
    for (String field :
        List.of("organizationId", "assetId", "gatewayId", "deviceId", "deviceUuid")) {
      var event = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
      var payload = (com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload");
      if (field.equals("deviceUuid")) payload.put(field, UUID.randomUUID().toString());
      else payload.put(field, 2);
      assertThatThrownBy(() -> sourceConfigurations.accept(event))
          .isInstanceOf(ResponseStatusException.class);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isZero();
  }

  @Test
  void sourceEventsRejectInvalidVersionsMappingsAndTime() {
    for (com.fasterxml.jackson.databind.JsonNode invalid :
        List.<com.fasterxml.jackson.databind.JsonNode>of(
            json.valueToTree(1.5), json.valueToTree("1"), json.valueToTree(true))) {
      var event = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
      ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("payload"))
          .set("configurationVersion", invalid);
      assertThatThrownBy(() -> sourceConfigurations.accept(event))
          .isInstanceOf(ResponseStatusException.class);
    }
    var invalidTime = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalidTime.path("payload"))
        .put("changedAt", "not-a-time");
    assertThatThrownBy(() -> sourceConfigurations.accept(invalidTime))
        .isInstanceOf(ResponseStatusException.class);
    var duplicateFields = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            duplicateFields.path("payload").path("profile").path("fields"))
        .put("humidity", "temperature");
    assertThatThrownBy(() -> sourceConfigurations.accept(duplicateFields))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isZero();
  }

  @Test
  void managementActivationPreservesLegacyBufferButRequiresContextForNewReadings() {
    sourceConfigurations.accept(sourceConfiguration(1, "probe-a", NOW.minusSeconds(10)));
    assertThat(
            service
                .ingest(
                    GATEWAY,
                    UUID.randomUUID(),
                    "local-key",
                    List.of(reading(1, NOW.minusSeconds(20), 4.0)),
                    "legacy-buffer")
                .accepted())
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY,
                    UUID.randomUUID(),
                    "local-key",
                    List.of(reading(2, NOW, 4.0)),
                    "missing-source"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(1);
  }

  @Test
  void replacementDoesNotBreakConfirmedReadingRetries() {
    sourceConfigurations.accept(sourceConfiguration(1, "probe-a", NOW.minusSeconds(30)));
    var pending = sourced(1, NOW.minusSeconds(20), "probe-a");
    var batch = UUID.randomUUID();
    service.ingest(GATEWAY, batch, "local-key", List.of(pending), "before-replacement");
    sourceConfigurations.accept(sourceConfiguration(2, "probe-b", NOW.minusSeconds(10)));
    var retry = service.ingest(GATEWAY, batch, "local-key", List.of(pending), "after-replacement");
    assertThat(retry.accepted()).isZero();
    assertThat(retry.duplicated()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentSourceEventRedeliveryAppliesOneRevision() throws Exception {
    var event = sourceConfiguration(1, "probe-a", NOW.minusSeconds(30));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> sourceConfigurations.accept(event));
      var second = executor.submit(() -> sourceConfigurations.accept(event));
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isEqualTo(1);
  }

  @Test
  void sourceConfigurationTimeCannotMoveBackwards() {
    sourceConfigurations.accept(sourceConfiguration(1, "probe-a", NOW.minusSeconds(10)));
    assertThatThrownBy(
            () ->
                sourceConfigurations.accept(
                    sourceConfiguration(2, "probe-b", NOW.minusSeconds(20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("backwards");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_assignment_revisions", Long.class))
        .isEqualTo(1);
  }

  Reading canonical(
      long sequence,
      String source,
      Integer profile,
      Integer version,
      String temperatureUnit,
      String humidityUnit) {
    return new Reading(
        DEVICE,
        sequence,
        NOW,
        4.0,
        60.0,
        null,
        null,
        source,
        profile,
        version,
        temperatureUnit,
        humidityUnit);
  }

  @Test
  void twoNormalizedProfilesUseTheSameEvaluationForOneHundredReadingsEach() {
    var secondDevice = UUID.fromString("33333333-3333-3333-3333-333333333333");
    jdbc.update(
        "INSERT INTO"
            + " device_replica(iot_device_id,organization_id,uuid,asset_id,gateway_id,location_id,reading_frequency_seconds,updated_at)"
            + " VALUES (2,1,?,2,1,1,10,?)",
        secondDevice.toString(),
        Timestamp.from(NOW));
    jdbc.update(
        "INSERT INTO"
            + " safe_range_replica(asset_id,organization_id,minimum_temperature,maximum_temperature,minimum_humidity,maximum_humidity,alert_threshold_minutes,settings_version,updated_at)"
            + " VALUES (2,1,2,8,40,80,0,1,?)",
        Timestamp.from(NOW));
    var readings = new ArrayList<Reading>();
    for (int i = 0; i < 100; i++) {
      double temperature = (i % 2 == 0) ? 4 : 12;
      Instant at = NOW.minusSeconds(100 - i);
      readings.add(
          new Reading(
              DEVICE, i + 1, at, temperature, 60.0, null, null, "mqtt-source", 1, 1, "C", "%RH"));
      readings.add(
          new Reading(
              secondDevice,
              i + 1,
              at,
              temperature,
              60.0,
              null,
              null,
              "http-source",
              3,
              1,
              "C",
              "%RH"));
    }
    assertThat(service.ingest(GATEWAY, UUID.randomUUID(), "local-key", readings, "two-profiles"))
        .isEqualTo(new BatchResult(200, 0));
    for (String source : List.of("mqtt-source", "http-source")) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM sensor_readings WHERE source_id=?", Long.class, source))
          .isEqualTo(100);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM sensor_readings WHERE source_id=? AND out_of_range=true",
                  Long.class,
                  source))
          .isEqualTo(50);
    }
  }

  @Test
  void canonicalContextIsPersistedAndPublishedWithoutChangingRangeEvaluation() {
    var reading = canonical(1, "camara-lacteos", 2, 1, "C", "%RH");
    assertThat(
            service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading), "canonical"))
        .isEqualTo(new BatchResult(1, 0));
    var row =
        jdbc.queryForMap(
            "SELECT"
                + " source_id,profile_version,schema_version,temperature_unit,humidity_unit,out_of_range"
                + " FROM sensor_readings");
    assertThat(row.get("source_id")).isEqualTo("camara-lacteos");
    assertThat(((Number) row.get("profile_version")).intValue()).isEqualTo(2);
    assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
    assertThat(row.get("temperature_unit")).isEqualTo("C");
    assertThat(row.get("humidity_unit")).isEqualTo("%RH");
    assertThat(row.get("out_of_range")).isEqualTo(false);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE event_type='readings.ingested'",
                String.class))
        .contains("camara-lacteos", "profileVersion", "temperatureUnit");
  }

  @Test
  void incompatibleCanonicalContextRejectsTheEntireBatchBeforeAnyWrite() {
    var invalid =
        List.of(
            canonical(2, "camara", 1, 2, "C", "%RH"),
            canonical(2, "camara", 1, 1, "F", "%RH"),
            canonical(2, "camara", 1, 1, "C", "fraction"),
            canonical(2, "camara", null, 1, "C", "%RH"),
            canonical(2, "camara", 0, 1, "C", "%RH"),
            canonical(2, "invalid source", 1, 1, "C", "%RH"),
            canonical(2, null, 1, 1, "C", "%RH"));
    for (var row : invalid) {
      assertThatThrownBy(
              () ->
                  service.ingest(
                      GATEWAY,
                      UUID.randomUUID(),
                      "local-key",
                      List.of(reading(1, NOW, 4), row),
                      "reject"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("400");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_batches", Long.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
    }
  }

  @Test
  void canonicalContextIsPartOfTheStableBatchFingerprint() {
    var key = UUID.randomUUID();
    var row = canonical(1, "camara", 1, 1, "C", "%RH");
    service.ingest(GATEWAY, key, "local-key", List.of(row), "first");
    assertThat(service.ingest(GATEWAY, key, "local-key", List.of(row), "retry"))
        .isEqualTo(new BatchResult(0, 1));
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY,
                    key,
                    "local-key",
                    List.of(canonical(1, "camara", 2, 1, "C", "%RH")),
                    "changed"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void legacyBatchFingerprintRemainsCompatibleWithThePreviousReadingRecord() throws Exception {
    record LegacyReading(
        UUID deviceUuid,
        long sequenceNumber,
        Instant recordedAt,
        Double temperature,
        Double humidity,
        Integer batteryLevel,
        Integer signalStrength) {}
    var legacy = new LegacyReading(DEVICE, 1, NOW, 4.0, 60.0, null, null);
    var expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(List.of(legacy))));
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "legacy");
    assertThat(jdbc.queryForObject("SELECT payload_hash FROM telemetry_batches", String.class))
        .isEqualTo(expected);
    assertThat(jdbc.queryForObject("SELECT source_id FROM sensor_readings", String.class)).isNull();
  }

  @Test
  void restEndpointAcceptsEdgeCanonicalFieldsAndRejectsUnsupportedUnits() throws Exception {
    var row = new LinkedHashMap<String, Object>();
    row.put("deviceUuid", DEVICE);
    row.put("sequenceNumber", 1);
    row.put("recordedAt", NOW);
    row.put("temperature", 4);
    row.put("humidity", 60);
    row.put("sourceId", "camara");
    row.put("profileVersion", 1);
    row.put("schemaVersion", 1);
    row.put("temperatureUnit", "C");
    row.put("humidityUnit", "%RH");
    var body = Map.of("gatewayUuid", GATEWAY, "readings", List.of(row));
    mvc.perform(
            post("/api/v1/telemetry/batches")
                .contentType("application/json")
                .header("X-Gateway-Key", "local-key")
                .header("Idempotency-Key", UUID.randomUUID())
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isAccepted());
    row.put("temperatureUnit", "F");
    row.put("sequenceNumber", 2);
    mvc.perform(
            post("/api/v1/telemetry/batches")
                .contentType("application/json")
                .header("X-Gateway-Key", "local-key")
                .header("Idempotency-Key", UUID.randomUUID())
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isBadRequest());
    row.put("temperatureUnit", "C");
    for (Object invalidVersion : List.of(1.5, "1", true, 2147483648L)) {
      row.put("schemaVersion", invalidVersion);
      mvc.perform(
              post("/api/v1/telemetry/batches")
                  .contentType("application/json")
                  .header("X-Gateway-Key", "local-key")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .content(json.writeValueAsBytes(body)))
          .andExpect(status().isBadRequest());
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(1);
  }

  @Test
  void repeatedBatchHasNoDuplicateReadingsOrEvents() {
    var key = UUID.randomUUID();
    var rows = List.of(reading(1, NOW, 4));
    assertThat(service.ingest(GATEWAY, key, "local-key", rows, "test"))
        .isEqualTo(new BatchResult(1, 0));
    assertThat(service.ingest(GATEWAY, key, "local-key", rows, "test"))
        .isEqualTo(new BatchResult(0, 1));
    assertThat(service.ingest(GATEWAY, key, "local-key", rows, "test"))
        .isEqualTo(new BatchResult(0, 1));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1);
  }

  @Test
  void changedBodyCannotReuseBatchKey() {
    var key = UUID.randomUUID();
    service.ingest(GATEWAY, key, "local-key", List.of(reading(1, NOW, 4)), "test");
    assertThatThrownBy(
            () -> service.ingest(GATEWAY, key, "local-key", List.of(reading(2, NOW, 9)), "test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  void invalidDeviceRollsBackWholeBatch() {
    var unknown = new Reading(UUID.randomUUID(), 2, NOW, 4.0, null, null, null);
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY,
                    UUID.randomUUID(),
                    "local-key",
                    List.of(reading(1, NOW, 4), unknown),
                    "test"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class)).isZero();
  }

  @Test
  void missingRangeRollsBackEarlierWritesAndBatchClaim() {
    jdbc.update("DELETE FROM safe_range_replica");
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "test"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_batches", Long.class)).isZero();
  }

  @Test
  void wrongGatewayKeyWritesNothing() {
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY, UUID.randomUUID(), "wrong", List.of(reading(1, NOW, 4)), "test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class)).isZero();
  }

  @Test
  void gatewayCannotSpoofAnotherOrganizationDevice() {
    jdbc.update("UPDATE device_replica SET organization_id=2");
    assertThatThrownBy(
            () ->
                service.ingest(
                    GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "test"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void replayedHistoryDoesNotRegressCurrentState() {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(2, NOW, 4)), "test");
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(1, NOW.minusSeconds(60), 12)),
        "test");
    assertThat(jdbc.queryForObject("SELECT status FROM asset_current_state", String.class))
        .isEqualTo("NORMAL");
  }

  @Test
  void gapOpensOnceAndNewReadingClosesIt() {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "test");
    when(clock.instant()).thenReturn(NOW.plusSeconds(40));
    gaps.detect();
    gaps.detect();
    assertThat(jdbc.queryForObject("SELECT status FROM asset_current_state", String.class))
        .isEqualTo("NO_DATA");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='source.gap'", Long.class))
        .isEqualTo(1);
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(2, NOW.plusSeconds(40), 4)),
        "test");
    assertThat(jdbc.queryForObject("SELECT status FROM asset_current_state", String.class))
        .isEqualTo("NORMAL");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='source.gap'", Long.class))
        .isEqualTo(2);
  }

  @Test
  void delayedHeartbeatUsesServerReceiptTime() {
    service.heartbeat(GATEWAY, "local-key", NOW.minusSeconds(3600), 3);
    assertThat(
            jdbc.queryForObject("SELECT last_heartbeat_at FROM monitored_gateways", Timestamp.class)
                .toInstant())
        .isEqualTo(NOW);
  }

  @Test
  void thresholdsAreCommittedWithOutbox() {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 10)), "test");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='threshold.breached'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentRetriesRemainIdempotent() throws Exception {
    var rows = List.of(reading(1, NOW, 4));
    var key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.ingest(GATEWAY, key, "local-key", rows, "a"));
      var b = pool.submit(() -> service.ingest(GATEWAY, key, "local-key", rows, "b"));
      assertThat(a.get().accepted() + b.get().accepted()).isEqualTo(1);
    }
  }

  @Test
  void projectionIsOrganizationScoped() throws Exception {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "test");
    mvc.perform(get("/api/v1/assets/1/state").header("Authorization", "Bearer org-2"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/assets/1/state").header("Authorization", "Bearer org-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NORMAL"));
  }

  @Test
  void oversizedBatchReturns413() throws Exception {
    var rows = Collections.nCopies(501, reading(1, NOW, 4));
    assertThatThrownBy(() -> service.ingest(GATEWAY, UUID.randomUUID(), "local-key", rows, "test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");
  }

  @Test
  void missingSequenceNumberIsRejectedRatherThanDefaultingToZero() throws Exception {
    String body =
        """
{"gatewayUuid":"11111111-1111-1111-1111-111111111111","readings":[
{"deviceUuid":"22222222-2222-2222-2222-222222222222","recordedAt":"2026-10-09T20:00:00Z","temperature":4}]}
""";
    mvc.perform(
            post("/api/v1/telemetry/batches")
                .contentType("application/json")
                .content(body)
                .header("X-Gateway-Key", "local-key")
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class)).isZero();
  }

  @Test
  void missingStateWithMultipleDeviceReplicasEmitsOneGap() {
    jdbc.update(
        "INSERT INTO"
            + " device_replica(iot_device_id,organization_id,uuid,asset_id,gateway_id,location_id,reading_frequency_seconds,updated_at)"
            + " VALUES (2,1,?,1,1,1,10,?)",
        UUID.randomUUID().toString(),
        Timestamp.from(NOW));
    when(clock.instant()).thenReturn(NOW.plusSeconds(40));
    gaps.detect();
    gaps.detect();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_current_state", Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='source.gap'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void generatedOpenApiExposesTheHandoffOperationsAndRequiredReadingFields() throws Exception {
    var response =
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse();
    var doc =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
    assertThat(doc.at("/paths/~1api~1v1~1telemetry~1batches/post/operationId").asText())
        .isEqualTo("ingestTelemetryBatch");
    assertThat(doc.at("/paths/~1api~1v1~1telemetry~1heartbeats/post/operationId").asText())
        .isEqualTo("registerHeartbeat");
    assertThat(doc.at("/paths/~1api~1v1~1assets~1{assetId}~1state/get/operationId").asText())
        .isEqualTo("getAssetCurrentState");
    assertThat(doc.at("/paths/~1api~1v1~1assets~1states/get/operationId").asText())
        .isEqualTo("listAssetCurrentStates");
    assertThat(doc.at("/components/schemas/ReadingDto/required").toString())
        .contains("deviceUuid", "sequenceNumber", "recordedAt");
    assertThat(doc.at("/components/schemas/ReadingDto/properties").toString())
        .contains("sourceId", "profileVersion", "schemaVersion", "temperatureUnit", "humidityUnit");
    assertThat(doc.at("/components/securitySchemes/gatewayKey/name").asText())
        .isEqualTo("X-Gateway-Key");
    assertThat(doc.at("/components/schemas/TelemetryBatchResponse/properties").has("duplicated"))
        .isTrue();
    assertThat(doc.at("/components/schemas/AssetCurrentState/properties").has("safeRange"))
        .isTrue();
  }

  @Test
  void toleranceRequiresSustainedDeviationAndResetsAfterNormalReading() {
    jdbc.update("UPDATE safe_range_replica SET alert_threshold_minutes=1");
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(1, NOW.minusSeconds(120), 12)),
        "tolerance");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='threshold.breached'",
                Long.class))
        .isZero();
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(2, NOW.minusSeconds(61), 12)),
        "tolerance");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='threshold.breached'",
                Long.class))
        .isZero();
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(3, NOW.minusSeconds(60), 12), reading(4, NOW.minusSeconds(50), 13)),
        "tolerance");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='threshold.breached'",
                Long.class))
        .isEqualTo(1);
    service.ingest(
        GATEWAY,
        UUID.randomUUID(),
        "local-key",
        List.of(reading(5, NOW.minusSeconds(40), 6), reading(6, NOW.minusSeconds(30), 12)),
        "tolerance");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='threshold.breached'",
                Long.class))
        .isEqualTo(1);
  }

  @Test
  void settingsReplicaRejectsOldVersionsAndResetsToleranceOnlyForNewRules() {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 12)), "test");
    var payload = new HashMap<String, Object>();
    payload.put("assetId", 1);
    payload.put("settingsVersion", 2);
    payload.put("minimumTemperature", 1);
    payload.put("maximumTemperature", 7);
    payload.put("alertThresholdMinutes", 5);
    var envelope =
        json.valueToTree(
            Map.of(
                "eventId",
                UUID.randomUUID(),
                "eventType",
                "asset.settings-changed",
                "version",
                1,
                "organizationId",
                1,
                "payload",
                payload));
    settings.accept(envelope);
    settings.accept(envelope);
    assertThat(
            jdbc.queryForObject(
                "SELECT maximum_temperature FROM safe_range_replica WHERE asset_id=1",
                Double.class))
        .isEqualTo(7);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM threshold_windows", Long.class)).isZero();
    payload.put("settingsVersion", 1);
    payload.put("maximumTemperature", 99);
    settings.accept(
        json.valueToTree(
            Map.of(
                "eventId",
                UUID.randomUUID(),
                "eventType",
                "asset.settings-changed",
                "version",
                1,
                "organizationId",
                1,
                "payload",
                payload)));
    assertThat(
            jdbc.queryForObject(
                "SELECT maximum_temperature FROM safe_range_replica WHERE asset_id=1",
                Double.class))
        .isEqualTo(7);
    payload.put("settingsVersion", 3);
    assertThatThrownBy(
            () ->
                settings.accept(
                    json.valueToTree(
                        Map.of(
                            "eventId",
                            UUID.randomUUID(),
                            "eventType",
                            "asset.settings-changed",
                            "version",
                            1,
                            "organizationId",
                            2,
                            "payload",
                            payload))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class))
        .isEqualTo(2);
  }
}
