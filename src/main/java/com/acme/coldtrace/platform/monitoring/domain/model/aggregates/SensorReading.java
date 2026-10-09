package com.acme.coldtrace.platform.monitoring.domain.model.aggregates;

import com.acme.coldtrace.platform.monitoring.domain.model.events.SensorReadingRecordedEvent;
import com.acme.coldtrace.platform.shared.domain.model.aggregates.AbstractDomainAggregateRoot;


import java.time.OffsetDateTime;

/**
 * Sensor reading aggregate for the monitoring bounded context.
 * <p>
 * A sensor reading is a persisted telemetry event produced by an IoT device for
 * a monitored asset. The aggregate stores the organization, asset, device,
 * gateway and location identifiers captured at creation time so reports and
 * incident workflows can rely on historical context even if the operational
 * topology changes later.
 *
 * @since 1.0
 */

public class SensorReading extends AbstractDomainAggregateRoot<SensorReading> {
    private Long id;
    private Long organizationId;
    private Long assetId;
    private Long iotDeviceId;
    private Long gatewayId;
    private Long locationId;
    private Double temperature;
    private Double humidity;
    private Boolean outOfRange;
    private OffsetDateTime recordedAt;
    private Boolean motionDetected;
    private Boolean imageCaptured;
    private Integer batteryLevel;
    private Integer signalStrength;

    protected SensorReading() {
    }

    /**
     * Creates a sensor reading with already evaluated telemetry state.
     *
     * @param organizationId organization that owns the reading
     * @param assetId asset observed by the device
     * @param iotDeviceId device that produced the telemetry
     * @param gatewayId gateway used when the reading was captured
     * @param locationId location context captured from the asset
     * @param temperature optional temperature value
     * @param humidity optional humidity value
     * @param outOfRange computed risk flag
     * @param recordedAt time when the reading was captured
     * @param motionDetected optional motion flag
     * @param imageCaptured optional image capture flag
     * @param batteryLevel optional battery percentage
     * @param signalStrength optional signal percentage
     */
    public SensorReading(
            Long organizationId,
            Long assetId,
            Long iotDeviceId,
            Long gatewayId,
            Long locationId,
            Double temperature,
            Double humidity,
            Boolean outOfRange,
            OffsetDateTime recordedAt,
            Boolean motionDetected,
            Boolean imageCaptured,
            Integer batteryLevel,
            Integer signalStrength
    ) {
        this.organizationId = organizationId;
        this.assetId = assetId;
        this.iotDeviceId = iotDeviceId;
        this.gatewayId = gatewayId;
        this.locationId = locationId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.outOfRange = outOfRange;
        this.recordedAt = recordedAt;
        this.motionDetected = motionDetected;
        this.imageCaptured = imageCaptured;
        this.batteryLevel = batteryLevel;
        this.signalStrength = signalStrength;
    }

    /**
     * Rebuilds a sensor reading from persisted state.
     *
     * @param id persistence identifier
     * @param organizationId organization that owns the reading
     * @param assetId asset observed by the device
     * @param iotDeviceId device that produced the telemetry
     * @param gatewayId gateway used when the reading was captured
     * @param locationId location context captured from the asset
     * @param temperature optional temperature value
     * @param humidity optional humidity value
     * @param outOfRange computed risk flag
     * @param recordedAt time when the reading was captured
     * @param motionDetected optional motion flag
     * @param imageCaptured optional image capture flag
     * @param batteryLevel optional battery percentage
     * @param signalStrength optional signal percentage
     */
    public SensorReading(
            Long id,
            Long organizationId,
            Long assetId,
            Long iotDeviceId,
            Long gatewayId,
            Long locationId,
            Double temperature,
            Double humidity,
            Boolean outOfRange,
            OffsetDateTime recordedAt,
            Boolean motionDetected,
            Boolean imageCaptured,
            Integer batteryLevel,
            Integer signalStrength
    ) {
        this(organizationId, assetId, iotDeviceId, gatewayId, locationId, temperature, humidity, outOfRange,
                recordedAt, motionDetected, imageCaptured, batteryLevel, signalStrength);
        this.id = id;
    }

    /**
     * Registers the domain event emitted after telemetry is persisted.
     */
    public void onRecorded() {
        registerDomainEvent(SensorReadingRecordedEvent.from(this));
    }
    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getAssetId() { return assetId; }
    public Long getIotDeviceId() { return iotDeviceId; }
    public Long getGatewayId() { return gatewayId; }
    public Long getLocationId() { return locationId; }
    public Double getTemperature() { return temperature; }
    public Double getHumidity() { return humidity; }
    public Boolean getOutOfRange() { return outOfRange; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public Boolean getMotionDetected() { return motionDetected; }
    public Boolean getImageCaptured() { return imageCaptured; }
    public Integer getBatteryLevel() { return batteryLevel; }
    public Integer getSignalStrength() { return signalStrength; }
}
