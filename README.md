# ColdTrace Monitoring Service

Owns telemetry ingestion, the current asset-state projection and source-gap
detection. It persists readings, projection changes and integration events in
one transaction. Batch identities protect retries; a conflicting payload under
the same key returns 409. Historical replay does not regress the latest state.

## API

| Method | Path | Authentication |
|---|---|---|
| POST | `/api/v1/telemetry/batches` | Gateway key and idempotency key |
| POST | `/api/v1/telemetry/heartbeats` | Gateway key |
| GET | `/api/v1/assets/{assetId}/state` | Bearer token |
| GET | `/api/v1/assets/states` | Bearer token; optional location/status filters |

Limits: 1–500 readings per batch, chronological order, nonnegative sequence
numbers. User queries resolve organization through IAM and isolate all reads
by that organization. Schema ownership: `ct_monitoring`; migrations: Flyway.

## Configuration

| Variable | Purpose / default |
|---|---|
| PORT | HTTP port; 8083 |
| DB_URL, DB_USER, DB_PASSWORD | MySQL connection; service-owned schema/user |
| IDENTITY_URL | IAM URL; http://localhost:8090 |
| COLDTRACE_PUBSUB_EMULATOR_HOST | Opt-in emulator relay; host:port |
| SPRING_PROFILES_ACTIVE | `demo` explicitly provisions synthetic local fixtures |
| DEMO_SENSORS | Number of synthetic devices under the demo profile |

Swagger: `/swagger-ui.html`; OpenAPI: `/v3/api-docs`; probes:
`/actuator/health/liveness`, `/actuator/health/readiness`.

## Implementation boundaries

The domain has no Spring/JPA/provider dependency; ArchUnit checks this rule.
Device/range replicas currently use explicit local provisioning. The settings
event subscriber and historical data migration remain future work. Threshold
events currently use instantaneous deviation and WARNING; duration/severity
policy is pending. Pub/Sub publication currently supports the local emulator;
authenticated cloud publication is not yet implemented.

## Build and test

Requires Java 21 and Maven. Install the shared library from tag
`v0.1.0-sprint1` (`08ab5ee5321e3d7e5e4ec0b3c454a5f5da9bfcc3`) first:

```sh
mvn -B -f ../coldtrace-shared/pom.xml install
mvn -B verify
```

Build the standalone image with the sibling library as a named build context:

```sh
docker build --build-context shared=../coldtrace-shared -t coldtrace-monitoring-service:dev .
```

The CI workflow installs the pinned shared source, runs tests, builds the image
and uploads test reports. Local orchestration is in `coldtrace-infrastructure`.
