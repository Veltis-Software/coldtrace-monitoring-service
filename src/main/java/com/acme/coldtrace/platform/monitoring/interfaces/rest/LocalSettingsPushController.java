package com.acme.coldtrace.platform.monitoring.interfaces.rest;

import com.acme.coldtrace.platform.monitoring.application.SettingsConsumer;
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

  public LocalSettingsPushController(SettingsConsumer consumer, ObjectMapper json) {
    this.consumer = consumer;
    this.json = json;
  }

  public record Message(String data) {}

  public record Push(Message message) {}

  @PostMapping("/internal/pubsub/events")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void receive(@RequestBody Push push) throws Exception {
    consumer.accept(json.readTree(Base64.getDecoder().decode(push.message().data())));
  }
}
