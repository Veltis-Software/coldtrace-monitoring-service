package com.acme.coldtrace.platform.monitoring.domain;


import java.time.Instant;
import java.util.UUID;
public final class Telemetry {
  private Telemetry() {}
  public record Reading(UUID deviceUuid,long sequenceNumber,Instant recordedAt,Double temperature,Double humidity,Integer batteryLevel,Integer signalStrength) {}
  public record Gateway(long id,long organizationId,String keyHash,Instant lastHeartbeat,Instant provisionedAt) {}
  public record Device(long id,long organizationId,long assetId,long gatewayId,long locationId,int frequencySeconds) {}
  public record SafeRange(double minimumTemperature,double maximumTemperature,Double minimumHumidity,Double maximumHumidity,int toleranceMinutes) {
    public boolean breached(Reading r) {
      return (r.temperature()!=null && (r.temperature()<minimumTemperature || r.temperature()>maximumTemperature))
          || (r.humidity()!=null && ((minimumHumidity!=null && r.humidity()<minimumHumidity) || (maximumHumidity!=null && r.humidity()>maximumHumidity)));
    }
  }
  public record BatchResult(int accepted,int duplicated) {}
  public static boolean gap(Instant now,Instant lastSeen,int frequencySeconds) {
    return lastSeen.plusSeconds(Math.max(30L,3L*frequencySeconds)).isBefore(now);
  }
}
