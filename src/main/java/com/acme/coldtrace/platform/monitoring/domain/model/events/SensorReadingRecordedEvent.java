package com.acme.coldtrace.platform.monitoring.domain.model.events;

import com.acme.coldtrace.platform.monitoring.domain.model.aggregates.SensorReading;

import java.time.OffsetDateTime;

/**
 * Domain event raised when telemetry is recorded.
 *
 * @param sensorReadingId sensor reading identifier
 * @param organizationId owning organization identifier
 * @param assetId asset identifier
 * @param iotDeviceId IoT device identifier
 * @param outOfRange whether the reading is outside configured thresholds
 * @param recordedAt reading timestamp
 * @since 1.0
 */
public record SensorReadingRecordedEvent(
        Long sensorReadingId,
        Long organizationId,
        Long assetId,
        Long iotDeviceId,
        Boolean outOfRange,
        OffsetDateTime recordedAt
) {
    /**
     * Builds the event from a sensor reading aggregate.
     *
     * @param reading source aggregate
     * @return sensor-reading-recorded event
     */
    public static SensorReadingRecordedEvent from(SensorReading reading) {
        return new SensorReadingRecordedEvent(
                reading.getId(),
                reading.getOrganizationId(),
                reading.getAssetId(),
                reading.getIotDeviceId(),
                reading.getOutOfRange(),
                reading.getRecordedAt()
        );
    }
}
