# Canonical reading contract

Monitoring accepts the existing gateway batch endpoint with optional canonical
source context. For each reading, either omit all five context fields (legacy
Sprint 1 input), or provide all of them:

| Field | Accepted value |
|---|---|
| sourceId | Configured origin identifier, 1–64 ASCII letters, digits, underscores or hyphens |
| profileVersion | Positive integer identifying the interpretation profile |
| schemaVersion | 1 |
| temperatureUnit | C |
| humidityUnit | %RH |

The edge adapter converts Fahrenheit and timezone offsets before submitting the
reading. Monitoring validates the canonical units and version; it does not perform
a second unit conversion. `recordedAt` is parsed as an instant. Existing gateway
authentication and device ownership determine organization and asset context;
the source identifier never grants access to another device or organization.

```json
{
  "gatewayUuid": "11111111-1111-1111-1111-111111111111",
  "readings": [{
    "deviceUuid": "22222222-2222-2222-2222-222222222222",
    "sequenceNumber": 1,
    "recordedAt": "2026-11-03T19:00:00Z",
    "temperature": 4.0,
    "humidity": 60.0,
    "sourceId": "camara-lacteos",
    "profileVersion": 1,
    "schemaVersion": 1,
    "temperatureUnit": "C",
    "humidityUnit": "%RH"
  }]
}
```

The batch is authenticated before processing. Context validation for every
reading happens before claiming the idempotency key or writing readings,
projections or events. An incomplete or unsupported context returns HTTP 400
without writing any part of the batch. A reused key with a changed source/profile
returns HTTP 409. Exact retries return accepted/duplicated counts as before.

Flyway V8 adds nullable columns to the existing reading table. Legacy rows retain
null source context; no source identity is inferred retrospectively. Canonical
context is persisted in the same transaction as measurements and included in
`readings.ingested` and threshold event payloads. Event names and envelope versions
remain unchanged because the extra payload fields are additive.

The fingerprint serializer retains the original seven fields, their order and
null values for legacy batches. Consequently a retry from a gateway running the
previous increment keeps the same payload hash. Canonical fields participate in
the fingerprint only when source context is supplied.

Source association administration and profile-distribution authorization remain
Asset Management responsibilities. Monitoring projects its configuration events
into a local revision history and checks the effective source/profile during
ingestion. See [source assignments](source-assignments.md) for rollout, historical
buffer compatibility and the boundary between emulator and cloud delivery.

## Verification

The integration suite verifies canonical persistence and event context, atomic
rejection, changed-profile conflicts, previous-version fingerprints, REST field
mapping, generated OpenAPI, and 100 normalized readings per source profile. The
two profiles produce the same safe-range results without changing evaluation
rules. Run the normal service suite or supply `TEST_DB_*` for an isolated MySQL
database as described in the increment documentation. Tests delete fixture rows;
never point them at a production or shared development schema.
