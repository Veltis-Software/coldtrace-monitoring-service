package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.acme.coldtrace.platform.monitoring.application.SettingsConsumer;
import com.acme.coldtrace.platform.monitoring.application.SourceConfigurationConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "coldtrace.pubsub.local-push-enabled", havingValue = "true")
public class LocalSettingsPushController {
  private final SettingsConsumer consumer;
  private final ObjectMapper json;
  private final SourceConfigurationConsumer sources;

  public LocalSettingsPushController(
      SettingsConsumer consumer, ObjectMapper json, SourceConfigurationConsumer sources) {
    this.consumer = consumer;
    this.json = json;
    this.sources = sources;
  }

  public record Message(String data) {}

  public record Push(Message message) {}

  @PostMapping("/internal/pubsub/events")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void receive(@RequestBody Push push) throws Exception {
    var event = json.readTree(Base64.getDecoder().decode(push.message().data()));
    if ("source.configuration-changed".equals(event.path("eventType").asText()))
      sources.accept(event);
    else consumer.accept(event);
  }
}
