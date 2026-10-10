package com.acme.coldtrace.platform.monitoring.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LegacyIdentityClient {
  private final RestClient client;

  public LegacyIdentityClient(@Value("${coldtrace.identity.url}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public long organization(String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer "))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    try {
      var context =
          client
              .get()
              .uri("/api/v1/session/context")
              .header("Authorization", bearer)
              .retrieve()
              .body(SessionContext.class);
      if (context == null || context.organizationId() == null)
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      return context.organizationId();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
    } catch (org.springframework.web.client.RestClientException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Identity context unavailable");
    }
  }

  public record SessionContext(Long userId, Long organizationId) {}
}
