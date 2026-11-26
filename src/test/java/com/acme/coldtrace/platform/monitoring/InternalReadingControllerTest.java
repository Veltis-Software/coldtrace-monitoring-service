package com.acme.coldtrace.platform.monitoring;

import com.acme.coldtrace.platform.monitoring.interfaces.rest.InternalReadingController;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InternalReadingControllerTest {
  private static final String KEY = "isolated-reading-owner-contract-key-at-least-32-characters";
  private InternalReadingController controller;
  @BeforeEach void fixture() {
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("CREATE TABLE sensor_readings(id BIGINT,organization_id BIGINT,asset_id BIGINT,iot_device_id BIGINT,gateway_id BIGINT,location_id BIGINT,temperature DOUBLE,humidity DOUBLE,out_of_range BOOLEAN,recorded_at TIMESTAMP)");
    jdbc.update("INSERT INTO sensor_readings VALUES(1,7,20,30,40,50,4,60,FALSE,'2026-11-01 10:00:00'),(2,8,20,30,40,50,5,61,TRUE,'2026-11-01 11:00:00')");
    controller = new InternalReadingController(jdbc, KEY);
  }
  @Test void ownerReadIsScopedAndPreservesUtcMeasurement() {
    var rows = controller.recent(7, 1, KEY);
    assertEquals(1, rows.size());
    assertEquals("2026-11-01T10:00Z", rows.getFirst().recordedAt().toString());
    assertEquals(4.0, rows.getFirst().temperature());
    assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.detail(7, 2, KEY)).getStatusCode().value());
  }
  @Test void unboundedQueriesAreRejected() {
    assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.recent(7, 1001, KEY)).getStatusCode().value());
  }
  @Test void browserTokenCannotReadOwnerEvidence() {
    assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.recent(7, 1, "Bearer token")).getStatusCode().value());
  }
}
