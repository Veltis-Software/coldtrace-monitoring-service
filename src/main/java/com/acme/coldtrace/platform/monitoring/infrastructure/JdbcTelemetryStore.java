package com.acme.coldtrace.platform.monitoring.infrastructure;

import com.acme.coldtrace.platform.monitoring.domain.Telemetry.*;
import com.acme.coldtrace.platform.monitoring.domain.TelemetryStore;
import com.acme.coldtrace.shared.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** All SQL executes against this service's schema. Gateway locks serialize concurrent retries. */
@Repository
public class JdbcTelemetryStore implements TelemetryStore {
  private final JdbcTemplate jdbc; private final ObjectMapper json;
  public JdbcTelemetryStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
  private Timestamp ts(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
  private Instant instant(Timestamp value) { return value==null ? null : value.toInstant(); }
  public Gateway lockGateway(UUID uuid) {
    var rows=jdbc.query("SELECT * FROM monitored_gateways WHERE uuid=? FOR UPDATE", (rs,n)->new Gateway(rs.getLong("gateway_id"),rs.getLong("organization_id"),rs.getString("api_key_hash"),instant(rs.getTimestamp("last_heartbeat_at")),instant(rs.getTimestamp("updated_at"))),uuid.toString());
    return rows.isEmpty()?null:rows.get(0);
  }
  public Device device(UUID uuid) {
    var rows=jdbc.query("SELECT * FROM device_replica WHERE uuid=?",(rs,n)->new Device(rs.getLong("iot_device_id"),rs.getLong("organization_id"),rs.getLong("asset_id"),rs.getLong("gateway_id"),rs.getLong("location_id"),rs.getInt("reading_frequency_seconds")),uuid.toString());
    return rows.isEmpty()?null:rows.get(0);
  }
  public SafeRange range(long asset,long org) {
    var rows=jdbc.query("SELECT * FROM safe_range_replica WHERE asset_id=? AND organization_id=?",(rs,n)->new SafeRange(rs.getDouble("minimum_temperature"),rs.getDouble("maximum_temperature"),(Double)rs.getObject("minimum_humidity"),(Double)rs.getObject("maximum_humidity"),rs.getInt("alert_threshold_minutes")),asset,org);
    return rows.isEmpty()?null:rows.get(0);
  }
  public void claimBatch(long gatewayId,UUID key,String fingerprint,Instant now) {
    var hashes=jdbc.queryForList("SELECT payload_hash FROM telemetry_batches WHERE gateway_id=? AND idempotency_key=?",String.class,gatewayId,key.toString());
    if(!hashes.isEmpty()) { if(!hashes.get(0).equals(fingerprint)) throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED"); }
    else jdbc.update("INSERT INTO telemetry_batches(gateway_id,idempotency_key,payload_hash,created_at) VALUES (?,?,?,?)",gatewayId,key.toString(),fingerprint,ts(now));
  }
  public boolean exists(Device d,Reading r) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings WHERE iot_device_id=? AND recorded_at=? AND sequence_number=?",Long.class,d.id(),ts(r.recordedAt()),r.sequenceNumber())>0;
  }
  public long insert(Device d,Reading r,boolean breached,Instant now) {
    var aggregate=new com.acme.coldtrace.platform.monitoring.domain.model.aggregates.SensorReading(
        d.organizationId(),d.assetId(),d.id(),d.gatewayId(),d.locationId(),r.temperature(),r.humidity(),breached,
        r.recordedAt().atOffset(java.time.ZoneOffset.UTC),null,null,r.batteryLevel(),r.signalStrength());
    var holder=new GeneratedKeyHolder();
    jdbc.update(connection->{
      var s=connection.prepareStatement("INSERT INTO sensor_readings(organization_id,asset_id,iot_device_id,gateway_id,location_id,sequence_number,temperature,humidity,out_of_range,recorded_at,battery_level,signal_strength,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
      Object[] values={aggregate.getOrganizationId(),aggregate.getAssetId(),aggregate.getIotDeviceId(),aggregate.getGatewayId(),aggregate.getLocationId(),r.sequenceNumber(),aggregate.getTemperature(),aggregate.getHumidity(),aggregate.getOutOfRange(),ts(aggregate.getRecordedAt().toInstant()),aggregate.getBatteryLevel(),aggregate.getSignalStrength(),ts(now),ts(now)};
      for(int i=0;i<values.length;i++) s.setObject(i+1,values[i]);return s;
    },holder);
    return Objects.requireNonNull(holder.getKey()).longValue();
  }
  public void project(Device d,Reading r,long id,boolean breached,Instant now,String correlation) {
    var states=jdbc.queryForList("SELECT status,last_recorded_at FROM asset_current_state WHERE asset_id=? FOR UPDATE",d.assetId());
    if(!states.isEmpty()) {
      var prior=states.get(0);
      var at=SqlTimes.instant(prior.get("last_recorded_at"));
      if(at!=null && r.recordedAt().isBefore(at)) return; // Replayed history never regresses current state.
      if("NO_DATA".equals(prior.get("status"))) event("source.gap",d.assetId(),d.organizationId(),Map.of("assetId",d.assetId(),"gatewayId",d.gatewayId(),"iotDeviceId",d.id(),"state","CLOSED","lastSeenAt",r.recordedAt()),now,correlation);
      jdbc.update("UPDATE asset_current_state SET status=?,last_reading_id=?,last_temperature=?,last_humidity=?,last_recorded_at=?,gap_opened_at=NULL,updated_at=? WHERE asset_id=?",breached?"OUT_OF_RANGE":"NORMAL",id,r.temperature(),r.humidity(),ts(r.recordedAt()),ts(now),d.assetId());
    } else jdbc.update("INSERT INTO asset_current_state(asset_id,organization_id,location_id,status,last_reading_id,last_temperature,last_humidity,last_recorded_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",d.assetId(),d.organizationId(),d.locationId(),breached?"OUT_OF_RANGE":"NORMAL",id,r.temperature(),r.humidity(),ts(r.recordedAt()),ts(now));
  }
  public void event(String type,long aggregate,long org,Object payload,Instant now,String correlation) {
    var event=EventEnvelope.create(type,now,org,correlation,payload);
    try {
      jdbc.update("INSERT INTO outbox_events(event_id,event_type,aggregate_id,organization_id,correlation_id,payload,occurred_at) VALUES (?,?,?,?,?,?,?)",event.eventId().toString(),type,aggregate,org,correlation,json.writeValueAsString(event),ts(now));
    } catch(Exception e) { throw new IllegalStateException("Cannot persist outbox event",e); }
  }
  public void heartbeat(long gateway,Instant now,Integer pending) {
    jdbc.update("UPDATE monitored_gateways SET last_heartbeat_at=?,pending_readings=?,updated_at=? WHERE gateway_id=?",ts(now),pending,ts(now),gateway);
  }
}
