package com.acme.coldtrace.platform.monitoring.domain;


import java.time.Instant;
import java.util.*;
import com.acme.coldtrace.platform.monitoring.domain.Telemetry.*;
public interface TelemetryStore {
  Gateway lockGateway(UUID uuid);
  Device device(UUID uuid);
  SafeRange range(long assetId,long organizationId);
  void claimBatch(long gatewayId,UUID key,String fingerprint,Instant now);
  boolean exists(Device device,Reading reading);
  long insert(Device device,Reading reading,boolean outOfRange,Instant now);
  void project(Device device,Reading reading,long readingId,boolean outOfRange,Instant now,String correlation);
  void event(String type,long aggregate,long organizationId,Object payload,Instant now,String correlation);
  void heartbeat(long gatewayId,Instant now,Integer pending);
}
