# Project Audit

## Scope and Runtime

The repository contains a React 18/Vite/Tailwind frontend, Django REST Framework backend, Django migrations and tests, Playwright tests, deployment configuration, and QA documents. The audit inspected frontend routing/pages/components/auth/API/CSS, backend settings/URLs/models/serializers/permissions/views/calculation code/migrations/tests, and README/deployment/test documentation.

```text
Browser -> React Router -> Axios API client -> Django REST API -> Django ORM -> configured database
```

Axios attaches Bearer access tokens, attempts refresh after 401 responses, and clears stored state on refresh failure. Auth context restores cached user/token and verifies via `/auth/me/`. Customer/staff live views refresh every five seconds. Admin requests health, services, and analytics at load and explicit refresh.

Render deploys `backend` as a Python web service and runs migrations on startup. `DATABASE_URL` is accepted; local settings support SQLite, PostgreSQL, and MySQL. No database file or production connection details are available.

## Frontend Inventory

| Surface | Components and interactions |
| --- | --- |
| App/navigation | Public landing/login/register/unauthorized routes; protected customer/staff/admin routes; catch-all redirects home; common navbar/footer. |
| Navbar | Brand/home/features/how-it-works links; role badge; authenticated dashboard link; logout; responsive menu drawer. |
| Landing | Hero and CTA, static queue preview, feature grid, three-step flow, bottom CTA. Preview content is illustrative, not API data. |
| Login | Email/password, show/hide password, required checks, expired/error/loading states, demo-login buttons, registration link. “Forgot?” is presentational and has no handler. |
| Register | Name/email/phone/password/confirmation, length and matching checks, role selector, API errors/loading. Backend omits role and creates USER only; the selector is misleading and must not grant privileges. |
| Customer dashboard | Light theme; active token/cancel, live service summary, service cards/join, history table, loading/empty/error/success states. Initial load includes history; 5-second polling refreshes only token/status. |
| Staff dashboard | Dark glass theme; service selector, metrics, current token/customer/status, call-next/start/complete/recall/skip, service overview, messages/errors/loading; 5-second status/current-token polling. |
| Admin dashboard | Dark glass theme; manual refresh, health/DB/service/waiting metrics, average wait, volume bars, throughput, hourly distribution, service table, system/admin panels. No visible service CRUD controls. |
| Unauthorized | Access denied message, account role, dashboard and home navigation. |
| Styling | Dark radial-gradient background, glass cards/inputs, indigo buttons, role badges and scrollbar. Customer dashboard intentionally overrides with gray/white styling. |

## Backend Findings

- Custom `accounts.User` uses email as unique identifier, roles `USER`/`STAFF`/`ADMIN`, active/staff/superuser flags, profile fields, timestamps, and Django password storage.
- Registration normalizes email, checks duplicate email and name length, uses minimum password length 6, omits role, and defaults public signups to USER.
- Auth includes register, login, refresh, profile GET/PATCH, logout/refresh blacklist, and health. Admin user list implementation was inspected but is not part of the checked-in root URL routes.
- `Service` has unique name, prefix, description, average time, active flag, timestamps. API supports list/create/retrieve/update/delete; write methods are ADMIN-only. React admin only lists services.
- Queue statuses: WAITING, CALLED, SERVING, COMPLETED, SKIPPED, CANCELLED. Customer cancellation accepts WAITING or CALLED; staff/admin call next, start, complete, skip, recall.
- Call-next orders by `joined_at` and uses `select_for_update(skip_locked=True)` inside an atomic transaction. This is database-dependent; SQLite requires serialized writes/conditional updates instead.
- Analytics are admin-only: completed-token duration from `joined_at` to `completed_at` per service, completed throughput grouped by date (latest seven returned), join-hour distribution, and service token volume.
- Token generator counts all tokens for a service and returns `<UPPERCASE_PREFIX>-<3-digit sequence>`. It is not safe against concurrent inserts without serialization; model has no unique token constraint.
- People-ahead counts only earlier `WAITING` tokens for the same service when current token is WAITING. Estimated minutes are zero when no one is ahead; otherwise `people_ahead * max(average_service_time, 1)`.

## Database Findings

Migrations define `accounts_user`, `services_service`, and `queues_queuetoken`, plus Django auth/admin/session/contenttypes and SimpleJWT blacklist tables. Custom primary keys are `BigAutoField`. Queue foreign keys cascade on user/service deletion; `served_by` becomes null on user deletion. Queue indexes cover `(service,status,joined_at)`, `(user,status)`, and `(status,joined_at)`. Service name and email are unique; token number is indexed but not unique. No existing database file was found, so physical schema/data verification was not possible.

## Existing Verification Evidence

`TEST_CASES.md` lists 36 baseline scenarios and reports six browser/E2E cases blocked by environment. The existing test report claims 30 passed and documents a resolved public role-escalation defect. These are repository-authored historical claims, not runs performed during this migration session. Java tests and screenshot comparisons have not been run.

## Risks and Gaps

- Production database vendor and records are inaccessible from source alone.
- Frontend registration role controls conflict with the backend's USER-only contract.
- Landing copy advertises alerts, QR entry, AI routing, SMS, and uptime/throughput claims not implemented as corresponding backend workflows in inspected code.
- README demo credentials require explicit seed data and must not be provisioned automatically in production.
- No Java/Maven project exists in the source tree at audit time.