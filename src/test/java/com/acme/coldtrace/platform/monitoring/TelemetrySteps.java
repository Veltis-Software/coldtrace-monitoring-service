package com.acme.coldtrace.platform.monitoring;

import static org.assertj.core.api.Assertions.*;

import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import io.cucumber.spring.CucumberContextConfiguration;
import java.util.*;

@CucumberContextConfiguration
public class TelemetrySteps extends TelemetryIntegrationTest {
  @Before
  public void reset() {
    seed();
  }

  @Given("a provisioned gateway and one reading")
  public void provisioned() {}

  @When("the gateway submits the same batch three times")
  public void retry() {
    repeatedBatchHasNoDuplicateReadingsOrEvents();
  }

  @Then("one reading and one outbox event are persisted")
  public void persisted() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class))
        .isEqualTo(1);
  }

  @When("the gateway submits the batch with an invalid key")
  public void unauthorized() {
    wrongGatewayKeyWritesNothing();
  }

  @Then("the batch is rejected and no reading is persisted")
  public void rejected() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings", Long.class)).isZero();
  }

  @When("no contact is received for forty seconds")
  public void silent() {
    service.ingest(GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "bdd");
    org.mockito.Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(40));
    gaps.detect();
  }

  @Then("the asset state is NO_DATA and one gap event is persisted")
  public void gap() {
    assertThat(jdbc.queryForObject("SELECT status FROM asset_current_state", String.class))
        .isEqualTo("NO_DATA");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type='source.gap'", Long.class))
        .isEqualTo(1);
  }

  @When("the gateway replays an older out-of-range reading")
  public void replay() {
    replayedHistoryDoesNotRegressCurrentState();
  }

  @Then("the asset current state remains NORMAL")
  public void current() {
    assertThat(jdbc.queryForObject("SELECT status FROM asset_current_state", String.class))
        .isEqualTo("NORMAL");
  }

  @When("the user queries current states after a valid reading")
  public void queryStates() {
    service.ingest(
        GATEWAY, UUID.randomUUID(), "local-key", List.of(reading(1, NOW, 4)), "bdd-state");
  }

  @Then("the matching page contains one NORMAL asset and another organization sees none")
  public void isolatedPage() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/assets/states?locationId=1&status=NORMAL&page=0&size=1")
                .header("Authorization", "Bearer org-1"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total")
                .value(1))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.items[0].assetId")
                .value(1))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.items[0].status")
                .value("NORMAL"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/assets/states")
                .header("Authorization", "Bearer org-2"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total")
                .value(0))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items")
                .isEmpty());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/assets/states?locationId=2")
                .header("Authorization", "Bearer org-1"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total")
                .value(0));
  }
}
