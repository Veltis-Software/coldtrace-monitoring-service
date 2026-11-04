package com.acme.coldtrace.platform.monitoring.domain;

import java.time.Instant;

public record SourceAssignment(
    long configurationVersion, String sourceId, int profileVersion, Instant effectiveAt) {}
