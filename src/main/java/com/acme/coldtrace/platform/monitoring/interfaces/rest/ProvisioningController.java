package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.acme.coldtrace.platform.monitoring.infrastructure.ReplicaProvisioning;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProvisioningController {
  private final ReplicaProvisioning provisioning;

  public ProvisioningController(ReplicaProvisioning provisioning) {
    this.provisioning = provisioning;
  }

  public record Request(long gatewayId, String gatewayApiKey) {}

  public record Result(int devicesProvisioned) {}

  @PostMapping("/api/v1/telemetry/provisioning")
  public Result synchronize(
      @RequestBody Request request,
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    return new Result(
        provisioning.synchronize(bearer, request.gatewayId(), request.gatewayApiKey()));
  }
}
