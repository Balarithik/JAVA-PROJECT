# Smart Queue Management System

A JavaFX desktop application for managing service queues with customer, staff and administrator workflows. The project uses Java 25, JavaFX, Maven, JDBC and SQLite.

## Current features

### Customer / student

- Join a service queue as a guest without creating an account.
- Register and sign in with an email address and password.
- Join a queue while signed in and retain token history in the account.
- View the token number, service, status, people ahead and estimated waiting time.
- Cancel a token while it is waiting or called.
- View the live service overview and recent token history.

### Staff / operator

- Sign in using an account created by an administrator.
- View waiting, called, serving and skipped tokens for a service.
- Call the next waiting customer in FIFO order.
- Start serving, complete or skip a called token.
- Recall a skipped token assigned to the same operator.
- Prevent multiple active assignments to one operator and prevent one operator from changing another operator's token.

### Administrator

- Create staff accounts and activate or deactivate staff accounts.
- Add service departments and activate or deactivate services.
- View average waiting time, daily completed-token throughput, hourly arrivals and service volume.
- Prevent deactivation of a service with active tokens or a staff account with an assigned active token.

## Technology stack

| Layer             | Technology                          |
| ----------------- | ----------------------------------- |
| Desktop UI        | JavaFX 25, FXML and CSS             |
| Application logic | Java 25                             |
| Persistence       | SQLite through JDBC (`sqlite-jdbc`) |
| Build             | Apache Maven                        |
| Tests             | JUnit 5                             |

> **Database note:** The accompanying project report describes a MySQL database (`smart_queue_db`). The current codebase still uses SQLite for local persistence. This README documents the implementation that is actually in this repository; a MySQL migration has not been completed.

## Requirements

- JDK 25
- Apache Maven 3.9 or newer

Check the installed tools:

```shell
java -version
mvn -version
```

run commands :
$env:PATH += ";C:\Users\Public\Downloads\apache-maven-3.10.0-bin\apache-maven-3.10.0\bin"
$env:SMARTQUEUE_ADMIN_EMAIL = "99250040058@klu.ac.in"
$env:SMARTQUEUE_ADMIN_PASSWORD = "Bala@2007"

## First-time setup

The application creates its SQLite schema and seeds the initial service departments on first launch. There are no hard-coded administrator credentials. Configure the first administrator through environment variables before launching the application for the first time.

### Windows PowerShell

```powershell
$env:SMARTQUEUE_ADMIN_EMAIL = "admin@example.com"
$env:SMARTQUEUE_ADMIN_PASSWORD = "Use-A-Strong-Password-Here"
mvn javafx:run
```

### macOS / Linux

```bash
export SMARTQUEUE_ADMIN_EMAIL="admin@example.com"
export SMARTQUEUE_ADMIN_PASSWORD="Use-A-Strong-Password-Here"
mvn javafx:run
```

The administrator password must contain at least 12 characters. The application creates the initial administrator only when these variables are set and that email does not already exist. After the account is created, you can remove the variables from the shell; the account remains in the database. Sign in through **Operator Login** and use the Administrator Console to create staff accounts.

Customer accounts can be registered from **Customer Login**. Guests can continue joining queues without an account.

## Build, test and run

Run these commands from the repository root:

```shell
mvn clean test
```

Launch the application:

```shell
mvn javafx:run
```

The project compiles for Java 25 and uses JavaFX 25.0.5. Maven downloads the required dependencies on the first build.

## Data storage

By default, the database file is stored at:

- Windows: `%APPDATA%\SmartQueue\smartqueue.db`
- macOS / Linux: `~/.smartqueue/smartqueue.db`

Set `SMARTQUEUE_DATA_DIR` to move the application data to another directory:

```powershell
$env:SMARTQUEUE_DATA_DIR = "D:\SmartQueueData"
```

The database is created automatically. Back up `smartqueue.db` before deleting or replacing it; doing so removes locally stored customer accounts, staff accounts, services and queue history.

## Project structure

```text
src/
├── main/
│   ├── java/com/smartqueue/
│   │   ├── config/          # Application paths and configuration
│   │   ├── controller/      # JavaFX screen and interaction controller
│   │   ├── database/        # SQLite connection and schema initialization
│   │   ├── model/           # Users, services, tokens and analytics models
│   │   ├── repository/      # JDBC data-access layer
│   │   ├── security/        # Password hashing and verification
│   │   ├── seed/            # Initial service data
│   │   └── service/         # Queue, authentication, admin and analytics logic
│   └── resources/
│       ├── css/             # Application styling
│       └── fxml/            # JavaFX layout
└── test/java/com/smartqueue/service/
    ├── AdminServiceTest.java
    ├── AuthServiceTest.java
    └── QueueServiceTest.java
```

## Queue rules and security

- Each service maintains its own FIFO sequence.
- Customers can cancel their own tokens only while the token is waiting or called.
- Staff actions are limited to tokens assigned to the signed-in operator.
- Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes, not as plain text.
- Staff and administrator screens require authenticated accounts; the first administrator is provisioned through environment variables.

## Known scope

QR-code scanning and real-time push notifications are not implemented in this version. They are possible future enhancements. MySQL support is also pending; SQLite is the active database implementation.
