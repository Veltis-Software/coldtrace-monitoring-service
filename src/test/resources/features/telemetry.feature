@US47 @ADR0006
Feature: Recover telemetry without duplicate readings
  Scenario: A gateway resends an acknowledged batch three times
    Given a provisioned gateway and one reading
    When the gateway submits the same batch three times
    Then one reading and one outbox event are persisted

  Scenario: A gateway presents an invalid key
    Given a provisioned gateway and one reading
    When the gateway submits the batch with an invalid key
    Then the batch is rejected and no reading is persisted

  @US46 @ADR0007
  Scenario: A gateway is silent after the gap threshold
    Given a provisioned gateway and one reading
    When no contact is received for forty seconds
    Then the asset state is NO_DATA and one gap event is persisted

  @US04 @ADR0011
  Scenario: An older reading is replayed after a newer reading
    Given a provisioned gateway and one reading
    When the gateway replays an older out-of-range reading
    Then the asset current state remains NORMAL

  @US04 @US16 @ADR0011
  Scenario: Current states are filtered by organization and location
    Given a provisioned gateway and one reading
    When the user queries current states after a valid reading
    Then the matching page contains one NORMAL asset and another organization sees none
