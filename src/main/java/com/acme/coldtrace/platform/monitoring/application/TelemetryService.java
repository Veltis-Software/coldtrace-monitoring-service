package com.acme.coldtrace.platform.monitoring.application;

import com.acme.coldtrace.platform.monitoring.domain.*;
import com.acme.coldtrace.platform.monitoring.domain.Telemetry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelemetryService {
  private final TelemetryStore store;
  private final Clock clock;
  private final PasswordEncoder passwords;
  private final ObjectMapper json;

  public TelemetryService(
      TelemetryStore store, Clock clock, PasswordEncoder passwords, ObjectMapper json) {
    this.store = store;
    this.clock = clock;
    this.passwords = passwords;
    this.json = json;
  }

  private Gateway authenticate(UUID gateway, String key) {
    var g = store.lockGateway(gateway);
    if (g == null || key == null || !passwords.matches(key, g.keyHash()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid gateway key");
    return g;
  }

  @Transactional
  public BatchResult ingest(
      UUID gateway, UUID idempotencyKey, String key, List<Reading> readings, String correlation) {
    var g = authenticate(gateway, key);
    var now = clock.instant();
    if (readings == null || readings.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Readings required");
    if (readings.size() > 500)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maximum 500 readings");
    var devices = new ArrayList<Device>();
    Instant previous = Instant.MIN;
    for (var r : readings) {
      if (r == null
          || r.deviceUuid() == null
          || r.sequenceNumber() < 0
          || r.recordedAt() == null
          || r.recordedAt().isBefore(previous)
          || r.recordedAt().isAfter(now.plusSeconds(60)))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or unordered reading");
      if ((r.temperature() != null && !Double.isFinite(r.temperature()))
          || (r.humidity() != null
              && (!Double.isFinite(r.humidity()) || r.humidity() < 0 || r.humidity() > 100))
          || (r.batteryLevel() != null && (r.batteryLevel() < 0 || r.batteryLevel() > 100)))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid measurement");
      previous = r.recordedAt();
      var d = store.device(r.deviceUuid());
      if (d == null || d.gatewayId() != g.id() || d.organizationId() != g.organizationId())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device not owned by gateway");
      devices.add(d);
    }
    try {
      store.claimBatch(
          g.id(),
          idempotencyKey,
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(readings))),
          now);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    int accepted = 0, duplicated = 0;
    var ingested = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < readings.size(); i++) {
      var r = readings.get(i);
      var d = devices.get(i);
      if (store.exists(d, r)) {
        duplicated++;
        continue;
      }
      var range = store.range(d.assetId(), d.organizationId());
      if (range == null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Safe range not provisioned");
      var breached = range.breached(r);
      long id = store.insert(d, r, breached, now);
      store.project(d, r, id, breached, now, correlation);
      accepted++;
      var payload = new LinkedHashMap<String, Object>();
      payload.put("readingId", id);
      payload.put("assetId", d.assetId());
      payload.put("iotDeviceId", d.id());
      payload.put("recordedAt", r.recordedAt());
      payload.put("temperature", r.temperature());
      payload.put("humidity", r.humidity());
      payload.put("outOfRange", breached);
      ingested.add(payload);
      if (breached) {
        var threshold = new LinkedHashMap<>(payload);
        threshold.put("severity", "WARNING");
        threshold.put("safeMinTemperature", range.minimumTemperature());
        threshold.put("safeMaxTemperature", range.maximumTemperature());
        threshold.put("safeMinHumidity", range.minimumHumidity());
        threshold.put("safeMaxHumidity", range.maximumHumidity());
        store.event(
            "threshold.breached", d.assetId(), d.organizationId(), threshold, now, correlation);
      }
    }
    if (!ingested.isEmpty())
      store.event(
          "readings.ingested",
          g.id(),
          g.organizationId(),
          Map.of("readings", ingested),
          now,
          correlation);
    return new BatchResult(accepted, duplicated);
  }

  @Transactional
  public void heartbeat(UUID gateway, String key, Instant sentAt, Integer pending) {
    if (sentAt == null || (pending != null && pending < 0))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid heartbeat");
    var g = authenticate(gateway, key);
    store.heartbeat(g.id(), clock.instant(), pending);
  }
}
