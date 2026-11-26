package com.acme.coldtrace.platform.monitoring.interfaces.rest.resources;

import java.time.OffsetDateTime;

/**
 * REST resource representing a persisted sensor reading.
 *
 * @param id persistence identifier
 * @param organizationId organization that owns the reading
 * @param assetId monitored asset identifier
 * @param iotDeviceId device that produced the reading
 * @param gatewayId gateway used when the reading was captured
 * @param locationId location context captured from the asset
 * @param temperature optional temperature value
 * @param humidity optional humidity value
 * @param outOfRange computed risk flag
 * @param isOutOfRange compatibility alias for frontend clients
 * @param recordedAt reading timestamp
 * @param motionDetected optional motion flag
 * @param imageCaptured optional image capture flag
 * @param batteryLevel optional battery percentage
 * @param signalStrength optional signal strength percentage
 * @since 1.0
 */
public record SensorReadingResource(
        Long id,
        Long organizationId,
        Long assetId,
        Long iotDeviceId,
        Long gatewayId,
        Long locationId,
        Double temperature,
        Double humidity,
        Boolean outOfRange,
        Boolean isOutOfRange,
        OffsetDateTime recordedAt,
        Boolean motionDetected,
        Boolean imageCaptured,
        Integer batteryLevel,
        Integer signalStrength
) {
}
