package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
    name = "AssetCurrentState",
    requiredProperties = {"assetId", "organizationId", "status", "updatedAt"})
public record AssetCurrentState(
    long assetId,
    long organizationId,
    @Schema(allowableValues = {"NORMAL", "OUT_OF_RANGE", "NO_DATA"}) String status,
    @Schema(nullable = true) Double lastTemperature,
    @Schema(nullable = true) Double lastHumidity,
    @Schema(nullable = true) Instant lastRecordedAt,
    SafeRange safeRange,
    Instant updatedAt) {
  @Schema(name = "SafeRange")
  public record SafeRange(
      double minTemperature,
      double maxTemperature,
      @Schema(nullable = true) Double minHumidity,
      @Schema(nullable = true) Double maxHumidity,
      int toleranceMinutes) {}
}
