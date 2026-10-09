package com.acme.coldtrace.platform.monitoring.infrastructure;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
/** Explicitly opt-in synthetic fixtures; never enabled in cloud configuration. */
@Configuration
@Profile("demo")
public class DemoProvisioner {
  @Bean ApplicationRunner provision(JdbcTemplate jdbc,PasswordEncoder passwords,
      @Value("${DEMO_SENSORS:1}") int sensors) {
    return args->{
      if(sensors<1 || sensors>1000) throw new IllegalArgumentException("DEMO_SENSORS must be 1..1000");
      var now=Timestamp.from(Instant.now());
      if(jdbc.queryForObject("SELECT COUNT(*) FROM monitored_gateways WHERE gateway_id=1",Long.class)==0)
        jdbc.update("INSERT INTO monitored_gateways(gateway_id,organization_id,uuid,api_key_hash,updated_at) VALUES (1,1,?,?,?)","11111111-1111-1111-1111-111111111111",passwords.encode("coldtrace-local-demo-key"),now);
      for(int i=0;i<sensors;i++) {
        long id=i+1;var base=UUID.fromString("22222222-2222-2222-2222-222222222222");var device=new UUID(base.getMostSignificantBits(),base.getLeastSignificantBits()+i);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM device_replica WHERE iot_device_id=?",Long.class,id)==0)
          jdbc.update("INSERT INTO device_replica(iot_device_id,organization_id,uuid,asset_id,gateway_id,location_id,reading_frequency_seconds,updated_at) VALUES (?,1,?,?,1,1,10,?)",id,device.toString(),id,now);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM safe_range_replica WHERE asset_id=?",Long.class,id)==0)
          jdbc.update("INSERT INTO safe_range_replica(asset_id,organization_id,minimum_temperature,maximum_temperature,minimum_humidity,maximum_humidity,alert_threshold_minutes,settings_version,updated_at) VALUES (?,1,2,8,40,80,5,1,?)",id,now);
      }
    };
  }
}
