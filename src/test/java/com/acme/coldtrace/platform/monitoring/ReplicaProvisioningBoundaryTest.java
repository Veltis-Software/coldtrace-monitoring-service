package com.acme.coldtrace.platform.monitoring;

import com.acme.coldtrace.platform.monitoring.infrastructure.ReplicaProvisioning;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:provisioning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver"})
class ReplicaProvisioningBoundaryTest {
  private static final List<String> identityRequests=new CopyOnWriteArrayList<>();
  private static final List<String> assetRequests=new CopyOnWriteArrayList<>();
  private static boolean canManage;
  private static boolean historyAvailable;
  private static HttpServer owner(boolean iam) {
    try {
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{
        String path=exchange.getRequestURI().getPath();
        (iam?identityRequests:assetRequests).add(path);
        assertEquals("Bearer actor",exchange.getRequestHeaders().getFirst("Authorization"));
        int status=200;
        String body;
        if(iam) {
          if(path.equals("/api/v1/session/context"))body="{\"userId\":1,\"organizationId\":7,\"permissions\":[\""+(canManage?"assets:manage":"assets:view")+"\"]}";
          else {status=404;body="{}";}
        } else {
          body=Map.of(
              "/api/v1/organizations/7/gateways/3","{\"id\":3,\"organizationId\":7,\"locationId\":1,\"uuid\":\"84ed3962-1ed4-4b4d-aaff-1212c3863f40\"}",
              "/api/v1/organizations/7/iot-devices","[{\"id\":10,\"organizationId\":7,\"gatewayId\":3,\"assetId\":20,\"uuid\":\"81780590-4e98-4c3a-a8f9-09cb72c5d905\",\"readingFrequencySeconds\":30}]",
              "/api/v1/organizations/7/assets/20","{\"id\":20,\"organizationId\":7,\"locationId\":1,\"name\":\"Camara Callao\"}",
              "/api/v1/organizations/7/assets/20/settings","{\"minimumTemperature\":2,\"maximumTemperature\":8,\"minimumHumidity\":50,\"maximumHumidity\":80,\"alertThresholdMinutes\":1}",
              "/api/v1/organizations/7/locations/1","{\"id\":1,\"name\":\"Callao\"}",
              "/api/v1/organizations/7/iot-devices/10/source-configuration/history","[]").get(path);
          if(body==null){status=404;body="{}";}
          if(path.endsWith("/history")&&!historyAvailable){status=503;body="{}";}
        }
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
      });
      server.start();return server;
    }catch(Exception failure){throw new IllegalStateException(failure);}
  }
  private static final HttpServer iam=owner(true), assets=owner(false);
  @DynamicPropertySource static void urls(DynamicPropertyRegistry properties) {
    properties.add("coldtrace.identity.url",()->"http://127.0.0.1:"+iam.getAddress().getPort());
    properties.add("coldtrace.asset.url",()->"http://127.0.0.1:"+assets.getAddress().getPort());
  }
  @Autowired ReplicaProvisioning provisioning;
  @Autowired JdbcTemplate jdbc;
  @BeforeEach void clear() {
    canManage=true;historyAvailable=true;identityRequests.clear();assetRequests.clear();
    for(String table:List.of("device_replica","safe_range_replica","monitored_gateways"))jdbc.update("DELETE FROM "+table);
  }
  @AfterAll static void stop(){iam.stop(0);assets.stop(0);}
  @Test void sessionAndAssetQueriesGoToDifferentOwners() {
    assertEquals(1,provisioning.synchronize("Bearer actor",3,"isolated-gateway-key-with-32-characters"));
    assertEquals(List.of("/api/v1/session/context"),identityRequests);
    assertTrue(assetRequests.stream().noneMatch(path->path.contains("session")));
    assertEquals(7L,jdbc.queryForObject("SELECT organization_id FROM device_replica WHERE iot_device_id=10",Long.class));
  }
  @Test void historyFailureLeavesNoHalfProvisionedReplica() {
    historyAvailable=false;
    assertThrows(Exception.class,()->provisioning.synchronize("Bearer actor",3,"isolated-gateway-key-with-32-characters"));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM monitored_gateways",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_replica",Integer.class));
  }
  @Test void readOnlyActorCannotProvisionKeysOrQueryAssetOwnership() {
    canManage=false;
    assertThrows(Exception.class,()->provisioning.synchronize("Bearer actor",3,"isolated-gateway-key-with-32-characters"));
    assertTrue(assetRequests.isEmpty());
  }
}
