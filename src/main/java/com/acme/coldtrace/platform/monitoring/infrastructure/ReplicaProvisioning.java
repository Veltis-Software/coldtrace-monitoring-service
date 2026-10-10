package com.acme.coldtrace.platform.monitoring.infrastructure;

import com.acme.coldtrace.shared.IdentitySession;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Explicit authenticated provisioning ACL. It is never called by telemetry ingestion. */
@Service
public class ReplicaProvisioning {
  private final RestClient legacy;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transaction;

  public ReplicaProvisioning(
      @Value("${coldtrace.asset.url:${coldtrace.identity.url}}") String url,
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      PlatformTransactionManager manager) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    legacy = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    this.jdbc = jdbc;
    this.passwords = passwords;
    transaction = new TransactionTemplate(manager);
  }

  public record Gateway(long id, long organizationId, long locationId, String uuid) {}

  public record Device(
      long id,
      long organizationId,
      Long gatewayId,
      Long assetId,
      String uuid,
      Integer readingFrequencySeconds) {}

  public record Asset(long id, long organizationId, Long locationId, String name) {}

  public record Location(long id, String name) {}

  public record Range(
      double minimumTemperature,
      double maximumTemperature,
      Double minimumHumidity,
      Double maximumHumidity,
      Integer alertThresholdMinutes) {}

  public record Snapshot(Device device, Asset asset, Range range, String locationName) {}

  private <T> T get(String bearer, String path, Class<T> type, Object... args) {
    return legacy.get().uri(path, args).header("Authorization", bearer).retrieve().body(type);
  }

  public int synchronize(String bearer, long gatewayId, String key) {
    if (bearer == null || !bearer.startsWith("Bearer "))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var session = get(bearer, "/api/v1/session/context", IdentitySession.class);
    if (session == null || !session.permits("assets:manage"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (key == null || key.length() < 16 || key.length() > 72)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Gateway key must contain 16 to 72 characters");
    long org = session.organizationId();
    var gateway =
        get(
            bearer,
            "/api/v1/organizations/{org}/gateways/{gateway}",
            Gateway.class,
            org,
            gatewayId);
    if (gateway == null || gateway.organizationId() != org)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    UUID.fromString(gateway.uuid());
    var devices = get(bearer, "/api/v1/organizations/{org}/iot-devices", Device[].class, org);
    var snapshots = new ArrayList<Snapshot>();
    for (var device : devices == null ? new Device[0] : devices) {
      if (device.organizationId() != org
          || device.gatewayId() == null
          || device.gatewayId() != gatewayId
          || device.assetId() == null) continue;
      UUID.fromString(device.uuid());
      if (device.readingFrequencySeconds() == null || device.readingFrequencySeconds() < 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid device frequency");
      var asset =
          get(
              bearer,
              "/api/v1/organizations/{org}/assets/{asset}",
              Asset.class,
              org,
              device.assetId());
      var range =
          get(
              bearer,
              "/api/v1/organizations/{org}/assets/{asset}/settings",
              Range.class,
              org,
              device.assetId());
      if (asset == null
          || asset.organizationId() != org
          || range == null
          || range.alertThresholdMinutes() == null
          || range.alertThresholdMinutes() < 0
          || range.minimumTemperature() > range.maximumTemperature())
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid asset configuration");
      var location =
          asset.locationId() == null
              ? null
              : get(
                  bearer,
                  "/api/v1/organizations/{org}/locations/{location}",
                  Location.class,
                  org,
                  asset.locationId());
      snapshots.add(new Snapshot(device, asset, range, location == null ? null : location.name()));
    }
    final String hash = passwords.encode(key);
    transaction.executeWithoutResult(
        status -> {
          var existing =
              jdbc.queryForList(
                  "SELECT organization_id FROM monitored_gateways WHERE gateway_id=? FOR UPDATE",
                  gatewayId);
          if (!existing.isEmpty()
              && ((Number) existing.get(0).get("organization_id")).longValue() != org)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gateway ownership mismatch");
          var now = Timestamp.from(Instant.now());
          jdbc.update(
              "INSERT INTO"
                  + " monitored_gateways(gateway_id,organization_id,uuid,api_key_hash,updated_at)"
                  + " VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                  + " uuid=VALUES(uuid),api_key_hash=VALUES(api_key_hash),updated_at=VALUES(updated_at)",
              gatewayId,
              org,
              gateway.uuid(),
              hash,
              now);
          for (var snapshot : snapshots) {
            var device = snapshot.device();
            var asset = snapshot.asset();
            var range = snapshot.range();
            var owners =
                jdbc.queryForList(
                    "SELECT organization_id FROM device_replica WHERE iot_device_id=?",
                    Long.class,
                    device.id());
            if (!owners.isEmpty() && owners.get(0) != org)
              throw new ResponseStatusException(HttpStatus.CONFLICT, "Device ownership mismatch");
            jdbc.update(
                "INSERT INTO"
                    + " device_replica(iot_device_id,organization_id,uuid,asset_id,gateway_id,location_id,reading_frequency_seconds,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " uuid=VALUES(uuid),asset_id=VALUES(asset_id),gateway_id=VALUES(gateway_id),location_id=VALUES(location_id),reading_frequency_seconds=VALUES(reading_frequency_seconds),updated_at=VALUES(updated_at)",
                device.id(),
                org,
                device.uuid(),
                asset.id(),
                gatewayId,
                asset.locationId(),
                device.readingFrequencySeconds(),
                now);
            jdbc.update(
                "INSERT INTO asset_replica(asset_id,organization_id,name,location_name,updated_at)"
                    + " VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " name=VALUES(name),location_name=VALUES(location_name),updated_at=VALUES(updated_at)",
                asset.id(),
                org,
                asset.name(),
                snapshot.locationName(),
                now);
            // Initial snapshot uses version zero. Subsequent versioned events always take
            // precedence.
            jdbc.update(
                "INSERT IGNORE INTO"
                    + " safe_range_replica(asset_id,organization_id,minimum_temperature,maximum_temperature,minimum_humidity,maximum_humidity,alert_threshold_minutes,settings_version,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,0,?)",
                asset.id(),
                org,
                range.minimumTemperature(),
                range.maximumTemperature(),
                range.minimumHumidity(),
                range.maximumHumidity(),
                range.alertThresholdMinutes(),
                now);
          }
        });
    return snapshots.size();
  }
}
