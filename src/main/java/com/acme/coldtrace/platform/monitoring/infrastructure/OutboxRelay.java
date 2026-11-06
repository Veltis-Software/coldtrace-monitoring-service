package com.acme.coldtrace.platform.monitoring.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Publishes committed outbox entries through the configured Pub/Sub transport. */
@Component
@ConditionalOnExpression(
    "${coldtrace.pubsub.cloud-enabled:false} or '${coldtrace.pubsub.emulator-host:}' != ''")
public class OutboxRelay {
  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private final JdbcTemplate jdbc;
  private final RestClient broker;
  private final ObjectMapper json;
  private final String project;

  public OutboxRelay(
      JdbcTemplate jdbc,
      ObjectMapper json,
      @Value("${coldtrace.pubsub.emulator-host:}") String host,
      @Value("${coldtrace.pubsub.cloud-enabled:false}") boolean cloud,
      @Value("${coldtrace.pubsub.project:coldtrace-local}") String project) {
    this.jdbc = jdbc;
    this.json = json;
    this.project = project;
    if (cloud && !host.isBlank())
      throw new IllegalArgumentException("Choose one Pub/Sub transport");
    if (!cloud && (host.isBlank() || host.contains("://") || host.contains("/")))
      throw new IllegalArgumentException("Expected emulator host:port");
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build());
    factory.setReadTimeout(java.time.Duration.ofSeconds(3));
    var publisher = RestClient.builder().requestFactory(factory);
    if (cloud) {
      if (project.isBlank() || project.equals("coldtrace-local"))
        throw new IllegalArgumentException("Cloud Pub/Sub requires a project");
      var metadata = RestClient.builder()
          .baseUrl("http://metadata.google.internal")
          .requestFactory(factory)
          .build();
      publisher.baseUrl("https://pubsub.googleapis.com")
          .requestInterceptor((request, body, execution) -> {
            var credentials = metadata.get()
                .uri("/computeMetadata/v1/instance/service-accounts/default/token")
                .header("Metadata-Flavor", "Google")
                .retrieve()
                .body(Map.class);
            if (credentials == null || !(credentials.get("access_token") instanceof String token)
                || token.isBlank())
              throw new IllegalStateException("Cloud Pub/Sub access token unavailable");
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
          });
    } else {
      publisher.baseUrl("http://" + host);
    }
    broker = publisher.build();
  }

  @Scheduled(
      fixedDelayString = "${coldtrace.outbox.interval-ms:500}",
      initialDelayString = "${coldtrace.outbox.initial-delay-ms:1000}")
  @Transactional
  public void publishPending() {
    // Acknowledgement may be lost after publication: consumers deduplicate by eventId.
    var rows =
        jdbc.queryForList(
            "SELECT id,event_id,event_type,payload FROM outbox_events WHERE published_at IS NULL"
                + " ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED");
    for (var row : rows) {
      long id = ((Number) row.get("id")).longValue();
      try {
        Object raw = row.get("payload");
        String payload =
            raw instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : raw.toString();
        var envelope = json.readTree(payload);
        var attributes =
            Map.of(
                "eventId",
                row.get("event_id").toString(),
                "eventType",
                row.get("event_type").toString(),
                "version",
                envelope.path("version").asText(),
                "organizationId",
                envelope.path("organizationId").asText());
        var message =
            Map.of(
                "data",
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                "attributes",
                attributes);
        var response =
            broker
                .post()
                .uri(
                    "/v1/projects/{project}/topics/{topic}:publish", project, row.get("event_type"))
                .body(Map.of("messages", List.of(message)))
                .retrieve()
                .body(Map.class);
        if (response == null
            || !(response.get("messageIds") instanceof List<?> ids)
            || ids.isEmpty()) throw new IllegalStateException("Missing publish acknowledgement");
        jdbc.update(
            "UPDATE outbox_events SET published_at=CURRENT_TIMESTAMP(6),attempts=attempts+1 WHERE"
                + " id=?",
            id);
      } catch (Exception failure) {
        jdbc.update("UPDATE outbox_events SET attempts=attempts+1 WHERE id=?", id);
        LOG.warn(
            "Outbox publication deferred for event {}: {}",
            row.get("event_id"),
            failure.getClass().getSimpleName());
        break; // Keep the event durable and retry on the next scheduled pass.
      }
    }
  }
}
