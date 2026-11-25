package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Calendar;
import java.util.TimeZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Bounded evidence queries outside the telemetry ingestion path. */
@RestController
@RequestMapping("/internal/monitoring/organizations/{organizationId}/readings")
public class InternalReadingController {
  public record ReadingSnapshot(Long id, Long organizationId, Long assetId, Long iotDeviceId,
      Long gatewayId, Long locationId, Double temperature, Double humidity, Boolean outOfRange,
      OffsetDateTime recordedAt) {}
  private final JdbcTemplate jdbc;
  private final String key;
  public InternalReadingController(JdbcTemplate jdbc, @Value("${coldtrace.internal.service-key:}") String key) {
    this.jdbc = jdbc; this.key = key;
  }
  private void authorize(String candidate, long org) {
    if (key.length() < 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    if (candidate == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (org < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }
  private static final RowMapper<ReadingSnapshot> MAPPER = (row, index) -> new ReadingSnapshot(
      row.getLong("id"), row.getLong("organization_id"), row.getLong("asset_id"), row.getLong("iot_device_id"),
      row.getLong("gateway_id"), row.getLong("location_id"), row.getObject("temperature", Double.class),
      row.getObject("humidity", Double.class), row.getBoolean("out_of_range"),
      row.getTimestamp("recorded_at", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().atOffset(ZoneOffset.UTC));
  @GetMapping
  public List<ReadingSnapshot> recent(@PathVariable long organizationId,
      @RequestParam(defaultValue = "1000") int limit,
      @RequestHeader(value = "X-Service-Key", required = false) String candidate) {
    authorize(candidate, organizationId);
    if (limit < 1 || limit > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return jdbc.query("SELECT * FROM sensor_readings WHERE organization_id=? ORDER BY recorded_at DESC,id DESC LIMIT ?", MAPPER, organizationId, limit);
  }
  @GetMapping("/{readingId}")
  public ReadingSnapshot detail(@PathVariable long organizationId, @PathVariable long readingId,
      @RequestHeader(value = "X-Service-Key", required = false) String candidate) {
    authorize(candidate, organizationId);
    return jdbc.query("SELECT * FROM sensor_readings WHERE organization_id=? AND id=?", MAPPER, organizationId, readingId)
        .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
