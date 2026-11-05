package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.acme.coldtrace.platform.monitoring.infrastructure.LegacyIdentityClient;
import com.acme.coldtrace.platform.monitoring.interfaces.rest.resources.SensorReadingResource;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Original browser read contract backed by Monitoring-owned telemetry. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/sensor-readings")
public class SensorReadingsController {
  private final JdbcTemplate jdbc;
  private final LegacyIdentityClient identity;
  public SensorReadingsController(JdbcTemplate jdbc,LegacyIdentityClient identity) { this.jdbc=jdbc;this.identity=identity; }
  private void authorize(String bearer,long org) {
    if(identity.organization(bearer)!=org)throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
  private static final RowMapper<SensorReadingResource> MAPPER=(row,index)->new SensorReadingResource(
      row.getLong("id"),row.getLong("organization_id"),row.getLong("asset_id"),row.getLong("iot_device_id"),
      row.getLong("gateway_id"),row.getLong("location_id"),row.getObject("temperature",Double.class),
      row.getObject("humidity",Double.class),row.getBoolean("out_of_range"),row.getBoolean("out_of_range"),
      row.getTimestamp("recorded_at",Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().atOffset(ZoneOffset.UTC),
      row.getObject("motion_detected",Boolean.class),row.getObject("image_captured",Boolean.class),
      row.getObject("battery_level",Integer.class),row.getObject("signal_strength",Integer.class));
  @GetMapping
  public List<SensorReadingResource> readings(@PathVariable long organizationId,
      @RequestHeader(value="Authorization",required=false)String bearer,
      @RequestParam(defaultValue="5000")int limit) {
    authorize(bearer,organizationId);
    if(limit<1||limit>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return jdbc.query("SELECT * FROM sensor_readings WHERE organization_id=? ORDER BY recorded_at DESC,id DESC LIMIT ?",MAPPER,organizationId,limit);
  }
  @GetMapping("/{sensorReadingId}")
  public SensorReadingResource reading(@PathVariable long organizationId,@PathVariable long sensorReadingId,
      @RequestHeader(value="Authorization",required=false)String bearer) {
    authorize(bearer,organizationId);
    return jdbc.query("SELECT * FROM sensor_readings WHERE organization_id=? AND id=?",MAPPER,organizationId,sensorReadingId)
        .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
