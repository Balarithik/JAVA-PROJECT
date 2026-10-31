# Smart Queue JavaFX Migration Report

**Status: Partial migration; not ready to replace the current centralized deployment.**

## Original and Target Architecture

The original is React/Vite -> Django REST + SimpleJWT -> Django ORM -> configured database, deployed through Render. `DATABASE_URL` can point to an external database; its actual production value and database file were not supplied.

The JavaFX implementation currently created in `javafx-app/` is JavaFX/FXML -> Java controllers/services -> JDBC repositories -> persistent SQLite. The database lives under `%APPDATA%/SmartQueue/smartqueue.db` by default, configurable with `SMARTQUEUE_DATA_DIR`. This is a **single-installation mode**. Multiple customers/staff desktops will not share data. A centralized Java backend API remains necessary before a Render/multi-terminal cutover; the Django deployment and original sources have not been removed or changed.

## Implemented

- Maven project targeting Java 21 and JavaFX controls/FXML, compatible with the available JDK 26 compiler.
- Native JavaFX landing, login, registration, customer, staff, admin, and unauthorized views. Customer dashboard retains its light theme; staff/admin views use a dark glass-style JavaFX stylesheet.
- SQLite JDBC database initialization aligned with the custom Django tables, foreign keys, useful queue indexes, schema version marker, WAL, busy timeout, prepared statements, and persistent application-data storage.
- Django-compatible PBKDF2-SHA256 password verification and compatible hashes for new accounts. Public registration creates USER only.
- Expiring seven-day local session token, with only its hash stored in SQLite and logout revocation. No password is stored in the session file.
- Queue token generation, active-token check, FIFO claim, people-ahead/wait estimate, cancellation, staff lifecycle, recall, customer history, service overview, and analytics service.
- Customer/staff background refresh on a five-second interval; customer history is excluded from recurring refresh. Admin refresh remains manual. Database work runs off the JavaFX UI thread.
- Native JavaFX `BarChart` analytics and `TableView` service/history/queue views.
- Explicit idempotent demo seed command; it creates missing demo rows only and is never run at startup.
- Audit, architecture, API, schema, UI mapping, feature matrix, and test report documents.

## Database and Data Integrity

No SQLite/database file was found in the repository. No production records were copied, modified, or deleted. The JDBC initializer creates missing tables only and does not drop/reset tables. Existing Django schema compatibility is based on committed model/migration definitions, not a physical database inspection. Back up and inspect an authorized source database before any import; verify its engine and password hashes first.

## Security

- Public sign-up cannot assign STAFF/ADMIN.
- Passwords use PBKDF2-SHA256 and constant-time digest comparison; existing Django iteration count is parsed from the stored hash.
- Session bearer token is random and revocable; SQLite stores only SHA-256 token hash and expiry.
- SQL values use prepared statements. SQLite foreign keys and serialized write transactions are enabled.
- Demo privileged accounts are created only by explicitly running `DemoDataSeeder`; do not use demo credentials in production.
- Local session token file uses the user's application-data directory and attempts owner-only POSIX permissions where supported; Windows ACL behavior was not independently audited.

## Test and Visual Results

`mvn test` passed **8/8 JUnit tests** on 2026-10-31. Coverage includes registration/hash behavior, FIFO/wait estimation, cancellation, staff transitions/recall, concurrent call-next, session persistence/revocation, seed idempotency, schema preservation/foreign keys, inactive services, and aggregate analytics. See [JAVA_TEST_REPORT.md](JAVA_TEST_REPORT.md).

Original/JavaFX screenshot comparisons have not been performed. The JavaFX screens are native controls, but matching dimensions, typography, spacing, iconography, loading/empty/error states, and full responsive behavior remains unverified. The launch smoke process remained active after loading JavaFX/SQLite runtimes without a captured startup exception; it was stopped and no visual acceptance is claimed.

## Known Limitations and Blockers

- Current JavaFX mode is local SQLite, not a centralized Java API client/server. Do not deploy separate desktop copies where users must share a queue.
- No production database or credentials are available, so data migration and imported-account login are unverified.
- Profile updates exist in the Java service but there is no profile editor, matching the lack of a visible React control.
- Admin service CRUD remains absent from JavaFX because the original admin UI only lists services.
- Screenshot comparison, automated UI testing, `jpackage` packaging, and complete acceptance coverage remain pending.
- No actual deployment topology was confirmed; Render configuration implies central hosting, so production cutover must wait for an architecture decision and shared backend.

## Build and Run

Prerequisites: JDK 21 or newer and Maven 3.9+. From `javafx-app/`:

```powershell
mvn test
mvn javafx:run
```

To explicitly create development demo services/accounts:

```powershell
mvn exec:java -Dexec.mainClass=com.smartqueue.seed.DemoDataSeeder
```

Default development credentials match the existing README demo accounts (`user@smartqueue.com`, `staff@smartqueue.com`, `admin@smartqueue.com`; password `Password123!`). Seed only a development database. The application database and session token are stored in `%APPDATA%/SmartQueue/`; set `SMARTQUEUE_DATA_DIR` to use another persistent directory. `mvn package` creates the Maven artifact. A native `jpackage` installer is not configured in this iteration.