package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.acme.coldtrace.platform.monitoring.infrastructure.JdbcTelemetryStore;
import com.acme.coldtrace.platform.monitoring.infrastructure.LegacyIdentityClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@SecurityScheme(
    name = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
@SecurityRequirement(name = "bearerAuth")
public class AssetStateController {
  private final LegacyIdentityClient identity;
  private final JdbcTemplate jdbc;
  private final JdbcTelemetryStore store;

  public AssetStateController(
      LegacyIdentityClient identity, JdbcTemplate jdbc, JdbcTelemetryStore store) {
    this.identity = identity;
    this.jdbc = jdbc;
    this.store = store;
  }

  private AssetCurrentState resource(Map<String, Object> row) {
    long asset = ((Number) row.get("asset_id")).longValue(),
        org = ((Number) row.get("organization_id")).longValue();
    var range = store.range(asset, org);
    var safe =
        range == null
            ? null
            : new AssetCurrentState.SafeRange(
                range.minimumTemperature(),
                range.maximumTemperature(),
                range.minimumHumidity(),
                range.maximumHumidity(),
                range.toleranceMinutes());
    return new AssetCurrentState(
        asset,
        org,
        (String) row.get("status"),
        (Double) row.get("last_temperature"),
        (Double) row.get("last_humidity"),
        com.acme.coldtrace.platform.monitoring.infrastructure.SqlTimes.instant(
            row.get("last_recorded_at")),
        safe,
        com.acme.coldtrace.platform.monitoring.infrastructure.SqlTimes.instant(
            row.get("updated_at")));
  }

  @Operation(
      operationId = "getAssetCurrentState",
      summary = "Read current state for the authenticated organization")
  @GetMapping("/api/v1/assets/{assetId}/state")
  public AssetCurrentState state(
      @PathVariable long assetId,
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    long org = identity.organization(bearer);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM asset_current_state WHERE asset_id=? AND organization_id=?",
            assetId,
            org);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return resource(rows.get(0));
  }

  @Operation(
      operationId = "listAssetCurrentStates",
      summary = "List projected states with organization and optional location filters")
  @GetMapping("/api/v1/assets/states")
  public StatePage states(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestParam(required = false) Long locationId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    if (page < 0
        || size < 1
        || size > 200
        || (status != null && !Set.of("NORMAL", "OUT_OF_RANGE", "NO_DATA").contains(status)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    long org = identity.organization(bearer);
    var params = new ArrayList<Object>();
    params.add(org);
    String where = " WHERE organization_id=?";
    if (locationId != null) {
      where += " AND location_id=?";
      params.add(locationId);
    }
    if (status != null) {
      where += " AND status=?";
      params.add(status);
    }
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM asset_current_state" + where, Long.class, params.toArray());
    params.add(size);
    params.add((long) page * size);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM asset_current_state" + where + " ORDER BY asset_id LIMIT ? OFFSET ?",
            params.toArray());
    return new StatePage(rows.stream().map(this::resource).toList(), page, size, total);
  }

  public record StatePage(List<AssetCurrentState> items, int page, int size, long total) {}
}
