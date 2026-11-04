# Source assignment synchronization

Asset Management owns configuration and replacement history. Its existing
transactional outbox publishes `source.configuration-changed`, version 1. Monitoring
projects those immutable revisions into its own schema through Flyway V9;
telemetry ingestion never calls Asset Management over the network.

## Event application

The consumer validates the organization, gateway, device UUID and monitored asset
against the provisioned replicas, and takes the same gateway lock as ingestion.
It validates integral contract/profile/configuration versions and supported
profile fields, transports and units. The device must already be provisioned.

Revisions start at 1 and advance consecutively. A missing predecessor returns 409
so transport redelivery can retry after the earlier event arrives. Duplicate
`eventId` plus the same payload is acknowledged without another write. Reusing an
event ID with different content, reusing a revision or moving its effective time
backwards returns 409. Payload and identity history remain immutable.

The local emulator routes the new topic to the existing
`/internal/pubsub/events` adapter. That adapter is enabled only by
`coldtrace.pubsub.local-push-enabled=true`. It is an emulator integration endpoint;
cloud deployment must use authenticated Pub/Sub delivery and must not expose this
local adapter publicly.

## Ingestion and replacement

For managed devices, `changedAt` defines the configuration's effective instant,
at microsecond database precision. A reading must identify the source/profile
effective at its `recordedAt`, with the canonical units already required by V8.
The last revision wins when multiple revisions share the same instant.

This preserves an old source's readings captured before replacement, even when
SQLite delivers them later. Existing stored duplicates retain normal idempotent
retry behavior. A retired source or missing canonical context after activation
returns 409 before batch claims, readings or outbox events are written. The whole
batch is rejected atomically. Before the first managed revision, legacy ingestion
remains compatible; no identity is assigned retrospectively to historical data.

The gateway authenticates the readings and is responsible for their capture
timestamps. Coordinate the Edge profile-file rollout with the administrative
change. A stale Edge configuration can generate readings that Monitoring rejects;
the agent retains unconfirmed batches rather than deleting or rewriting them.
Configuration conflicts require operational correction, while delayed control
events are retried by their transport.

## Edge rollout

Export the gateway's source-profile document from Asset Management and replace
the Edge configuration file atomically. Edge validates the complete document
before swapping its live registry; invalid or missing replacements leave the last
valid registry active. Its refresh interval defaults to five seconds. Mount the
configuration directory read-only into the container so atomic file replacement
on the host becomes visible; SQLite uses its existing writable persistent volume.

The configuration-file rollout and local broker routing are implemented here.
Fleet management, cloud OIDC delivery, and the deployment environment are separate
infrastructure concerns.

## Verification

The suite covers out-of-order delivery, deduplication including concurrent retries,
identity/tenant mismatches, invalid versions and timestamps, backwards effective
time, rejection without writes, legacy-buffer compatibility and source replacement
without breaking confirmed batch retries. Run against H2 or an isolated MySQL
database using the existing `TEST_DB_*` environment configuration.
