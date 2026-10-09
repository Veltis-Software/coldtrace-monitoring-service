package com.acme.coldtrace.platform.monitoring.infrastructure;

import java.sql.Timestamp;
import java.time.*;

/** MySQL DATETIME is stored in UTC; Connector/J returns LocalDateTime, H2 Timestamp. */
public final class SqlTimes {
  private SqlTimes() {}

  public static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
    throw new IllegalArgumentException("Unsupported SQL timestamp: " + value.getClass().getName());
  }
}
