# API to Java Mapping

Current endpoints are rooted at `/api`, use JSON, and carry `Authorization: Bearer <access>` when authenticated. The target architecture assigns JDBC to the authoritative Java backend, not independent client copies of a shared database.

| Current API | Contract / permission | Java target | Notes |
| --- | --- | --- | --- |
| `GET /health/` | Public health, version, database state | `HealthService` + backend health endpoint | Admin dashboard calls on load/manual refresh. |
| `POST /auth/register/` | Public; email/name/phone/password; response user + tokens | `AuthService.register` + repository | Always USER; reject/ignore client role. Password minimum 6. |
| `POST /auth/login/` | Public; email/password; user + access/refresh | `AuthService.login` | Django hash compatibility requires inspection before importing existing accounts. |
| `POST /auth/refresh/` | Refresh token -> rotated access/refresh | `SessionService.refresh` | Preserve rotation/blacklist semantics if retaining JWT. |
| `GET/PATCH /auth/me/` | Authenticated; retrieve/update own user detail | `UserService.profile/updateProfile` | Never allow role/active flag edits through profile updates. |
| `POST /auth/logout/` | Authenticated; optional refresh blacklist | `AuthService.logout` | Clear local session even if revocation fails. |
| `GET /services/` | Authenticated; active services generally; `?all=true` staff/admin behavior; admin may see inactive by default | `ServiceService.list` | Use actual view permission/query rules; no visible admin CRUD UI. |
| `POST /services/` | ADMIN only; service create | `ServiceService.create` | API capability only; not a current dashboard interaction. |
| `GET /services/{id}/` | Authenticated service detail | `ServiceService.get` | Preserve not-found behavior. |
| `PUT/PATCH/DELETE /services/{id}/` | ADMIN only | `ServiceService.update/delete` | No visible React admin controls; omit UI absent scope decision. |
| `POST /queue/join/` | Authenticated; `{service_id}`; active-service/no-active-token checks; returns token/position/wait | `QueueService.join` transaction | Sequence based on all prior service tokens, not only active tokens. |
| `GET /queue/my-token/` | Authenticated; active token or null | `QueueService.getMyActiveToken` | Active statuses: WAITING/CALLED/SERVING. |
| `GET /queue/status/` | Authenticated; active token and per-active-service waiting/current call summary | `QueueService.status` | Customer/staff refresh target every five seconds. |
| `GET /queue/history/` | Authenticated; latest 50 COMPLETED/SKIPPED/CANCELLED | `QueueService.history` | Descending `joined_at`. |
| `POST /queue/cancel/` | Authenticated; own WAITING/CALLED token; sets CANCELLED/completed_at | `QueueService.cancel` | Cannot cancel SERVING through this endpoint. |
| `POST /queue/call-next/` | STAFF/ADMIN; optional service_id, counter default Counter 1; FIFO by joined_at | `QueueService.callNext` transaction | Central JDBC process serializes claim; set called_at, counter, served_by. |
| `POST /queue/start/` | STAFF/ADMIN; optional token_id; CALLED -> SERVING, sets started_at | `QueueService.startServing` | Without ID, most recent called token assigned to current staff. |
| `POST /queue/complete/` | STAFF/ADMIN; SERVING or CALLED -> COMPLETED; sets completed_at | `QueueService.complete` | Preserve backend acceptance of CALLED as well as SERVING. |
| `POST /queue/skip/` | STAFF/ADMIN; CALLED -> SKIPPED; sets completed_at | `QueueService.skip` | Preserve completed_at behavior. |
| `POST /queue/recall/` | STAFF/ADMIN; CALLED or SKIPPED -> CALLED; resets called_at/completed_at; optional counter | `QueueService.recall` | Preserve staff ownership filter when token_id omitted. |
| `GET /queue/analytics/` | ADMIN only | `AnalyticsService` + JDBC aggregates | Completed duration is `completed_at - joined_at`; latest seven daily buckets; hourly joins; volume by service. |

## UI-to-API Call Patterns

- Login/register store tokens and user in browser localStorage; auth context verifies cached sessions with `/auth/me/`.
- Customer initial load requests services, active token, status, and history concurrently. Every five seconds, only active token/status are re-requested.
- Staff initial load requests services, queue status, and current user's active token. Every five seconds it refreshes status and active token. Actions call each staff endpoint with selected/current token ids.
- Admin initial load/manual refresh requests health, services, analytics concurrently. No automatic polling or service CRUD interaction is visible.

## Source Contract Quirks

- Staff dashboard stores `/my-token/` response envelope in `currentToken`, but staff action responses are bare token objects; a helper supports either nested or bare responses.
- Queue status selects current service token from CALLED/SERVING ordered by latest called_at. Service serializer `active_tokens_count` counts WAITING, CALLED, and SERVING tokens, not only waiting customers.
- Registration sends role, but backend serializer drops it and manager defaults USER.
- Queue model does not uniquely constrain token number; allocation is count-based. Java must serialize allocation and should add a unique constraint only after checking existing records for duplicates.