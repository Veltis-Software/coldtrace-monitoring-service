package com.acme.coldtrace.platform.monitoring.application;

import com.acme.coldtrace.platform.monitoring.domain.Telemetry;
import com.acme.coldtrace.platform.monitoring.infrastructure.JdbcTelemetryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.Map;

@Service
public class SourceGapDetector {
  private final JdbcTemplate jdbc;private final JdbcTelemetryStore store;private final Clock clock;
  public SourceGapDetector(JdbcTemplate jdbc,JdbcTelemetryStore store,Clock clock) {this.jdbc=jdbc;this.store=store;this.clock=clock;}
  @Scheduled(fixedDelayString="${coldtrace.gap.interval-ms:10000}",initialDelayString="${coldtrace.gap.initial-delay-ms:10000}")
  @Transactional
  public void detect() {
    var now=clock.instant();
    // Serialize with ingestion by locking gateway rows first; same lock ordering avoids deadlocks.
    jdbc.queryForList("SELECT gateway_id FROM monitored_gateways ORDER BY gateway_id FOR UPDATE");
    var devices=jdbc.queryForList("SELECT d.*,g.last_heartbeat_at,g.updated_at AS provisioned_at,s.last_recorded_at,s.status FROM device_replica d JOIN monitored_gateways g ON d.gateway_id=g.gateway_id LEFT JOIN asset_current_state s ON d.asset_id=s.asset_id ORDER BY d.asset_id,d.iot_device_id");
    for(var d:devices) {
      long asset=((Number)d.get("asset_id")).longValue(),org=((Number)d.get("organization_id")).longValue(),gateway=((Number)d.get("gateway_id")).longValue();
      var seen=com.acme.coldtrace.platform.monitoring.infrastructure.SqlTimes.instant(d.get("provisioned_at"));
      for(var column:new String[]{"last_heartbeat_at","last_recorded_at"}) { var value=com.acme.coldtrace.platform.monitoring.infrastructure.SqlTimes.instant(d.get(column));if(value!=null && value.isAfter(seen)) seen=value; }
      if(!Telemetry.gap(now,seen,((Number)d.get("reading_frequency_seconds")).intValue()) || "NO_DATA".equals(d.get("status"))) continue;
      int updated=jdbc.update("UPDATE asset_current_state SET status='NO_DATA',gap_opened_at=?,updated_at=? WHERE asset_id=? AND status<>'NO_DATA'",Timestamp.from(now),Timestamp.from(now),asset);
      if(d.get("status")==null && jdbc.queryForObject("SELECT COUNT(*) FROM asset_current_state WHERE asset_id=?",Integer.class,asset)==0) updated=jdbc.update("INSERT INTO asset_current_state(asset_id,organization_id,location_id,status,gap_opened_at,updated_at) VALUES (?,?,?,'NO_DATA',?,?)",asset,org,d.get("location_id"),Timestamp.from(now),Timestamp.from(now));
      if(updated>0) store.event("source.gap",asset,org,Map.of("assetId",asset,"gatewayId",gateway,"state","OPENED","lastSeenAt",seen),now,"gap-detector");
    }
  }
}
