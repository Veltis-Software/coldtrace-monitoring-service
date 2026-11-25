package com.acme.coldtrace.platform.monitoring.domain;

import java.time.Instant;
import java.util.UUID;

public final class Telemetry {
  private Telemetry() {}

  public record Reading(
      UUID deviceUuid,
      long sequenceNumber,
      Instant recordedAt,
      Double temperature,
      Double humidity,
      Integer batteryLevel,
      Integer signalStrength,
      String sourceId,
      Integer profileVersion,
      Integer schemaVersion,
      String temperatureUnit,
      String humidityUnit) {
    public Reading(
        UUID deviceUuid,
        long sequenceNumber,
        Instant recordedAt,
        Double temperature,
        Double humidity,
        Integer batteryLevel,
        Integer signalStrength) {
      this(
          deviceUuid,
          sequenceNumber,
          recordedAt,
          temperature,
          humidity,
          batteryLevel,
          signalStrength,
          null,
          null,
          null,
          null,
          null);
    }

    public boolean hasSourceContext() {
      return sourceId != null
          || profileVersion != null
          || schemaVersion != null
          || temperatureUnit != null
          || humidityUnit != null;
    }

    public boolean validSourceContext() {
      return !hasSourceContext()
          || (sourceId != null
              && sourceId.matches("[A-Za-z0-9_-]{1,64}")
              && profileVersion != null
              && profileVersion > 0
              && Integer.valueOf(1).equals(schemaVersion)
              && "C".equals(temperatureUnit)
              && "%RH".equals(humidityUnit));
    }
  }

  public record Gateway(
      long id, long organizationId, String keyHash, Instant lastHeartbeat, Instant provisionedAt) {}

  public record Device(
      long id,
      long organizationId,
      long assetId,
      long gatewayId,
      long locationId,
      int frequencySeconds) {}

  public record SafeRange(
      double minimumTemperature,
      double maximumTemperature,
      Double minimumHumidity,
      Double maximumHumidity,
      int toleranceMinutes) {
    public boolean breached(Reading r) {
      return (r.temperature() != null
              && (r.temperature() < minimumTemperature || r.temperature() > maximumTemperature))
          || (r.humidity() != null
              && ((minimumHumidity != null && r.humidity() < minimumHumidity)
                  || (maximumHumidity != null && r.humidity() > maximumHumidity)));
    }
  }

  public record BatchResult(int accepted, int duplicated) {}

  public static boolean gap(Instant now, Instant lastSeen, int frequencySeconds) {
    return lastSeen.plusSeconds(Math.max(30L, 3L * frequencySeconds)).isBefore(now);
  }
}
