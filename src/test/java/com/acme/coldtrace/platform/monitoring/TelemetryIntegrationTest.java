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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class TelemetryIntegrationTest {
  public static final UUID GATEWAY = UUID.fromString("11111111-1111-1111-1111-111111111111"),
      DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
  public static final Instant NOW = Instant.parse("2026-10-09T20:00:00Z");
  @Autowired TelemetryService service;
  @Autowired SourceGapDetector gaps;
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
            + " VALUES (1,1,2,8,40,80,5,1,?)",
        Timestamp.from(NOW));
  }

  public Reading reading(long seq, Instant at, double temperature) {
    return new Reading(DEVICE, seq, at, temperature, 60.0, null, null);
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
    assertThat(doc.at("/components/securitySchemes/gatewayKey/name").asText())
        .isEqualTo("X-Gateway-Key");
    assertThat(doc.at("/components/schemas/TelemetryBatchResponse/properties").has("duplicated"))
        .isTrue();
    assertThat(doc.at("/components/schemas/AssetCurrentState/properties").has("safeRange"))
        .isTrue();
  }
}
