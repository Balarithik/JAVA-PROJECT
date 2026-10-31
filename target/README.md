# Smart Queue Management System

## Project Overview

The Smart Queue Management System is designed to improve queue handling and reduce the inconvenience of long waits in hospitals, banks, educational institutions, and other public-service environments. Traditional queues often rely on standing in line and manually distributed tokens, leaving customers with little visibility into queue progress and contributing to overcrowding, wasted time, and avoidable manual errors.

The system digitizes this process: customers can obtain a token and monitor their position, while staff and administrators manage service queues and track token progression. The goal is to make queue status more transparent, reduce unnecessary physical waiting, and improve service operations. Estimated wait times and analytics support these workflows; notifications and predictive waiting-time features remain possible future extensions.

### Primary Objectives

- Reduce confusion and inconvenience associated with traditional queues.
- Automate token generation and queue progression.
- Show customers their token and periodically refreshed queue status.
- Help staff and administrators organize service operations.
- Improve accessibility, transparency, and customer experience.

### Expected Outcome

The system provides a structured foundation for more efficient queue handling, helping users plan their wait and service teams manage token progression. Future extensions may include mobile notifications, public digital displays, and improved wait-time prediction.

## Current Implementation

This checkout contains the native JavaFX desktop implementation. It uses Java services, JDBC repositories, and SQLite; it does not embed React or require a local Django server.

> **Deployment mode:** This checkout runs as a single-installation desktop app with a local SQLite database. Separate computers do not share queue data. For a shared multi-terminal queue, deploy a centralized Java backend instead of distributing independent SQLite copies.

## Project Contents

```text
workspace/
├── docs/                                  Migration audit and test reports
└── Smart-Queue-Management-System-main/
    ├── README.md
    └── javafx-app/                        JavaFX app, JDBC persistence, and JUnit tests
```

The legacy React/Django source is not included in this checkout. Its assessed behavior and endpoint mappings are documented in the sibling `docs/` directory.

## Requirements

- JDK 21 or newer. Maven compiles the code with `--release 21`.
- Apache Maven 3.9 or newer, available as `mvn` in `PATH`.
- A graphical desktop session for running JavaFX.

Verify the tools:

```powershell
java -version
mvn -version
```

If PowerShell reports that `mvn` is not recognized, install Maven and add its `bin` directory to `PATH`, then open a new terminal.

## Build, Test, and Run

From the repository directory that contains `javafx-app/`:

```powershell
cd .\javafx-app
mvn test
mvn package
mvn javafx:run
```

`mvn test` compiles the project and runs the JUnit/SQLite integration tests. `mvn package` creates `target/smart-queue-javafx-1.0.0.jar`. Use `mvn javafx:run` to launch the desktop app; the packaged JAR is not a self-contained installer and should not be launched with `java -jar` alone.

The window opens on the public landing screen. You can register a customer account there. To sign in as staff or admin during development, seed the local database first.

## Development Seed Data

Seeding is explicit and is never performed automatically when the app starts. From `javafx-app/`, run:

```powershell
mvn exec:java -Dexec.mainClass=com.smartqueue.seed.DemoDataSeeder
```

The seeder creates missing demo services and accounts. Existing records with those emails or service names are left unchanged. It does not seed queue tokens.

| Role | Email | Password |
| --- | --- | --- |
| Customer | `user@smartqueue.com` | `Password123!` |
| Staff | `staff@smartqueue.com` | `Password123!` |
| Admin | `admin@smartqueue.com` | `Password123!` |

These accounts and credentials are for local development only. Public registration creates `USER` accounts only; it cannot grant staff or admin privileges.

## Desktop Workflow

1. On first launch, the app creates the missing SQLite schema and opens the landing screen. It does not populate demo data automatically.
2. Register a customer, or use the seeded customer account.
3. In the customer dashboard, choose an active service, join its queue, view token position and estimated wait, cancel an eligible token, or inspect completed/cancelled/skipped history.
4. Sign in as staff to select a service, call the next FIFO token, start or complete service, skip a called customer, or recall a skipped token.
5. Sign in as admin to review database/system status, service overview, and queue analytics. Admin dashboard refresh is manual.

Customer and staff queue information refreshes in the background every five seconds. Customer history is not fetched on each live refresh. Database work runs off the JavaFX UI thread.

## Data and Configuration

On Windows, the default data directory is `%APPDATA%\SmartQueue\`:

- `smartqueue.db`: SQLite application database.
- `session.token`: local session token; the database stores its hash and expiry.

On macOS/Linux, the default is `~/.smartqueue/`. `SMARTQUEUE_DATA_DIR` overrides either default.

Set `SMARTQUEUE_DATA_DIR` before running Maven to use a different persistent directory. For example:

```powershell
$env:SMARTQUEUE_DATA_DIR = "$env:LOCALAPPDATA\SmartQueueDev"
mvn exec:java -Dexec.mainClass=com.smartqueue.seed.DemoDataSeeder
mvn javafx:run
```

Use the same environment variable for seeding and application runs, or they will use different database directories. The schema initializer creates missing tables and indexes; it does not drop or reset existing tables. Back up `smartqueue.db` before manually moving or modifying it.

## Architecture and Security

- JavaFX/FXML and JavaFX CSS provide the desktop interface.
- Controllers call Java services; repositories contain SQL and use prepared statements.
- SQLite uses foreign-key enforcement and serialized write transactions for queue operations.
- User passwords use PBKDF2-SHA256. Public registration always assigns the `USER` role.
- Local sessions expire after seven days and can be revoked at logout.
- No demo account is created at startup.

The application uses a local SQLite file per installation, so queues are not synchronized across computers. The existing Render/Django architecture was centralized; replacing it safely requires a central Java API and a verified database migration. See [Architecture Assessment](../docs/ARCHITECTURE_ASSESSMENT.md) and [Migration Report](../docs/MIGRATION_REPORT.md).

## Troubleshooting

| Symptom | Resolution |
| --- | --- |
| `mvn` is not recognized | Install Maven 3.9+ and add its `bin` folder to `PATH`; verify with `mvn -version`. |
| Build reports unsupported release 21 | Select JDK 21+ and verify `java -version` and `mvn -version` use the intended JDK. |
| No services appear | Run the explicit development seeder from `javafx-app/` using the same `SMARTQUEUE_DATA_DIR` as the app. |
| Demo login fails | Seed the database first; ensure the seeder and app use the same data directory. |
| Data appears different on another machine | Each installation has its own SQLite file; this version has no central synchronization service. |
| JavaFX cannot open a window | Run from a desktop session with a display available; the app is not a headless server. |

## Migration and Test Reports

- [Architecture Assessment](../docs/ARCHITECTURE_ASSESSMENT.md)
- [Project Audit](../docs/PROJECT_AUDIT.md)
- [Feature Parity Matrix](../docs/FEATURE_PARITY_MATRIX.md)
- [UI Component Mapping](../docs/UI_COMPONENT_MAPPING.md)
- [Database Schema Mapping](../docs/DATABASE_SCHEMA_MAPPING.md)
- [API to Java Mapping](../docs/API_TO_JAVA_MAPPING.md)
- [Java Test Report](../docs/JAVA_TEST_REPORT.md)
- [Migration Report](../docs/MIGRATION_REPORT.md)
