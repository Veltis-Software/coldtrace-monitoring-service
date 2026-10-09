package com.acme.coldtrace.platform.monitoring;
import com.acme.coldtrace.platform.monitoring.infrastructure.OutboxRelay;
import com.acme.coldtrace.shared.EventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class OutboxRelayTest {
  @Test void brokerFailureKeepsEventPendingAndRetryPublishesTheSameEnvelope() throws Exception {
    var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:outbox-test;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    jdbc.execute("CREATE TABLE outbox_events(id BIGINT PRIMARY KEY,event_id VARCHAR(36),event_type VARCHAR(100),payload CLOB,published_at TIMESTAMP,attempts INT DEFAULT 0)");
    var json=new ObjectMapper().registerModule(new JavaTimeModule());
    var event=EventEnvelope.create("threshold.breached",Instant.now(),1,"relay-test",Map.of("assetId",1));
    jdbc.update("INSERT INTO outbox_events(id,event_id,event_type,payload) VALUES (1,?,?,?)",event.eventId().toString(),event.eventType(),json.writeValueAsString(event));
    var fail=new java.util.concurrent.atomic.AtomicBoolean(true);var received=new java.util.ArrayList<String>();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{
      received.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
      byte[] body=(fail.get()?"{}":"{\"messageIds\":[\"published-1\"]}").getBytes();
      exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(fail.get()?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try {
      var relay=new OutboxRelay(jdbc,json,"127.0.0.1:"+server.getAddress().getPort(),"coldtrace-local");
      relay.publishPending();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL",Long.class)).isEqualTo(1);
      fail.set(false);relay.publishPending();relay.publishPending();
      assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events",Integer.class)).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL",Long.class)).isEqualTo(1);
      assertThat(received).hasSize(2);assertThat(received.get(0)).isEqualTo(received.get(1));
      assertThat(received.get(1)).contains(event.eventId().toString());
    } finally {server.stop(0);}
  }
}
