package com.acme.coldtrace.platform.monitoring.infrastructure;

import com.acme.coldtrace.shared.infrastructure.CorrelationFilter;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class MonitoringConfiguration {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CorrelationFilter correlationFilter() {
    return new CorrelationFilter();
  }
}
