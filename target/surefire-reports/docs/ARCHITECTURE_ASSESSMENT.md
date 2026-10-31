# Architecture and Deployment Assessment

## Current Deployment

- The repository is a decoupled React/Vite client and Django REST Framework API using SimpleJWT.
- `render.yaml` deploys the API as a Render web service and runs Django migrations at service startup.
- The service accepts `DATABASE_URL`. Django settings also support SQLite (default), PostgreSQL, and MySQL. The production database vendor/topology cannot be established because deployment secrets are not checked in.
- React sends user, service, queue, and analytics operations to the API. Customer and staff views poll selected queue endpoints every five seconds; the admin view is manually refreshed.
- Hosting the API behind a web service and accepting an external database URL implies a centralized deployment model. The checked-in files do not establish the number of users/devices or prove the production database is SQLite.
- No `.db`, `.sqlite`, or `.sqlite3` database file is present in the workspace. No user records or existing database can be migrated or backed up here.

## Migration Decision

JDBC must not create one unrelated queue database per desktop if multiple devices use the system. For deployment parity, the target architecture is:

```text
JavaFX desktop client -> Java backend API -> JDBC repositories -> one central database
```

JDBC belongs in the backend process that owns the database. SQLite JDBC is appropriate only when a single backend process owns the file and all desktop clients call that process; clients must not open the same SQLite file directly over a network share. If production currently uses PostgreSQL/MySQL, the corresponding JDBC driver and migration plan are required to preserve that database. The requested SQLite3 target cannot be reconciled with production records until the live database is identified.

For a single-machine installation, JavaFX -> Java services -> JDBC -> persistent SQLite is viable, but does not preserve cross-device queue visibility. That deployment mode must be explicit. Existing Django/React source and deployment remain untouched until a Java backend has parity and a verified data migration path.

## Current JavaFX Implementation Boundary

The first JavaFX iteration under `javafx-app/` currently uses direct JavaFX -> Java services -> SQLite JDBC. It is a single-installation mode and does **not** implement the central Java backend API in the target diagram above. Do not deploy separate copies for customers and counter staff or treat those SQLite files as a shared queue. The original Django service remains in place. A centralized Java backend and client API adapter are required before replacing the current Render deployment.

## Security and Consistency Constraints

- Public registration must assign `USER` regardless of submitted role.
- Existing Django password hashes must not be interpreted as plaintext or silently rewritten. Django PBKDF2 is expected for accounts created by the configured user model, but live hashes were unavailable.
- Queue allocation and staff transitions must be serialized in the authoritative JDBC backend using transactions and conditional state updates. SQLite write serialization is a safe single-writer equivalent; Django's `select_for_update(skip_locked=True)` must not be copied literally.
- Enable foreign keys for each SQLite JDBC connection. Persistent data belongs in an application data directory, never in `target/` or another build directory.
- Do not automatically seed users/services at startup. Existing seed commands are explicit development/testing operations.

## Verification Required Before Production Cutover

1. Confirm which database `DATABASE_URL` points to and whether deployed API traffic is shared across devices.
2. Obtain an authorized database backup and inspect the actual schema and password hashes.
3. Decide and document the central Java backend hosting model and configure one authoritative JDBC database.
4. Migrate transactionally, verify row counts/foreign keys, test rollback, then run parallel functional checks before retiring Django.

**Current status:** source audit completed and the local JDBC application is implemented/tested to a partial milestone. Production database access, real deployment topology, centralized Java API, visual comparison, and cutover remain unverified. No existing database was changed.