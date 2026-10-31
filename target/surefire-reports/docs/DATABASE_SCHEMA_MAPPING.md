# Database Schema Mapping

## Evidence and Limitation

This mapping is derived from Django models and committed initial migrations. No SQLite/database file exists in the workspace, and production database credentials/schema are unavailable. This is the declared Django schema, not a physical database inspection. No database was changed or backed up.

## Application Tables

| Django model/table | Fields and constraints | Relationships/indexes | JDBC target |
| --- | --- | --- | --- |
| `accounts.User` / `accounts_user` | BigAutoField `id`; password max 128; nullable last_login; is_superuser; unique indexed email max 255; name max 150; phone max 25; indexed role max 20 default USER; is_active; is_staff; created_at; updated_at | PermissionsMixin adds groups/user_permissions many-to-many tables and Django auth foreign keys | Preserve Django table/columns for in-place SQLite use, or use explicit versioned migration. Never copy passwords as plaintext. |
| `services.Service` / `services_service` | BigAutoField `id`; unique indexed name max 150; prefix max 5 default A; description; positive average_service_time default 5; indexed is_active; created_at; updated_at | Queue tokens cascade on delete | Match integer/boolean/text/timestamp fields and unique name. |
| `queues.QueueToken` / `queues_queuetoken` | BigAutoField `id`; indexed token_number max 30; indexed status max 20 default WAITING; indexed joined_at; called_at/started_at/completed_at nullable; counter_number max 50 default empty | user FK CASCADE; service FK CASCADE; served_by nullable FK SET_NULL. Indexes `(service_id,status,joined_at)`, `(user_id,status)`, `(status,joined_at)` | Enable `PRAGMA foreign_keys=ON`; preserve FK actions and indexes. Existing model does not uniquely constrain token_number; allocation must serialize and inspect duplicates before any constraint change. |
| Java local session / `smartqueue_session` | Java-only table: id, user_id, token_hash, created_at, expires_at, nullable revoked_at | user FK CASCADE; unique token_hash; index `(user_id,expires_at)` | Added by Java schema version 2. It stores SHA-256 token hashes only and is not part of Django's original schema. |

## Django Framework Tables

Installed Django auth, admin, sessions, contenttypes, and SimpleJWT blacklist apps add framework tables including `auth_group`, `auth_permission`, user/group permission join tables, `django_content_type`, `django_admin_log`, `django_session`, `django_migrations`, and token blacklist tables. Their exact schema depends on Django/app migration versions. Do not drop or reinterpret them during a custom-table migration.

## SQLite/JDBC Rules

- Store the database in a persistent OS application-data directory.
- Initialize schema through idempotent, versioned migrations; never drop/reset tables.
- Enable foreign keys for every connection and use prepared statements.
- Use transactions for token allocation, active-token checks, and staff status updates.
- Back up source data before migration and verify row counts, keys, foreign keys, indexes, and representative records before cutover.
- No migration can safely execute against an unavailable database. A future importer must detect actual Django password hash formats/iterations before enabling existing-account login.