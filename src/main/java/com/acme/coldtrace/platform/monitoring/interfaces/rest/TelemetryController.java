package com.acme.coldtrace.platform.monitoring.interfaces.rest;
import com.acme.coldtrace.platform.monitoring.application.TelemetryService;
import com.acme.coldtrace.platform.monitoring.domain.Telemetry.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.*;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/api/v1/telemetry")
@SecurityScheme(name="gatewayKey",type=SecuritySchemeType.APIKEY,in=SecuritySchemeIn.HEADER,paramName="X-Gateway-Key")
@SecurityRequirement(name="gatewayKey")
public class TelemetryController {
  private final TelemetryService service;
  public TelemetryController(TelemetryService service) {this.service=service;}
  @Schema(name="ReadingDto")
  public record ReadingDto(@NotNull UUID deviceUuid,@NotNull @Min(0) Long sequenceNumber,
      @NotNull Instant recordedAt,Double temperature,Double humidity,Integer batteryLevel,Integer signalStrength) {
    Reading reading() {return new Reading(deviceUuid,sequenceNumber,recordedAt,temperature,humidity,batteryLevel,signalStrength);}
  }
  @Schema(name="TelemetryBatchRequest")
  public record Batch(@NotNull UUID gatewayUuid,@NotNull @ArraySchema(minItems=1,maxItems=500) List<@Valid @NotNull ReadingDto> readings) {}
  @Schema(name="TelemetryBatchResponse",requiredProperties={"accepted","duplicated"})
  public record BatchResponse(int accepted,int duplicated) {}
  @Schema(name="HeartbeatRequest")
  public record Heartbeat(@NotNull UUID gatewayUuid,@NotNull Instant sentAt,Integer pendingReadings) {}
  @Operation(operationId="ingestTelemetryBatch",summary="Persist an ordered batch atomically with deduplication")
  @ApiResponse(responseCode="202",description="Committed; accepted and duplicated readings")
  @PostMapping("/batches")
  public ResponseEntity<BatchResponse> ingest(@Valid @RequestBody Batch body,@RequestHeader("Idempotency-Key") UUID key,@RequestHeader(value="X-Gateway-Key",required=false) String gatewayKey,HttpServletRequest request) {
    var result=service.ingest(body.gatewayUuid(),key,gatewayKey,body.readings().stream().map(ReadingDto::reading).toList(),String.valueOf(request.getAttribute("correlationId")));
    return ResponseEntity.accepted().body(new BatchResponse(result.accepted(),result.duplicated()));
  }
  @Operation(operationId="registerHeartbeat",summary="Register gateway contact using server receipt time")
  @ApiResponse(responseCode="204",description="Heartbeat recorded")
  @PostMapping("/heartbeats")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void heartbeat(@Valid @RequestBody Heartbeat body,@RequestHeader(value="X-Gateway-Key",required=false) String key) {service.heartbeat(body.gatewayUuid(),key,body.sentAt(),body.pendingReadings());}
}
