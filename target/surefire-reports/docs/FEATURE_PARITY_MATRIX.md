# Feature Parity Matrix

Statuses describe the current migration workspace, not historical React/Django behavior. “Implemented” refers to the current single-machine JavaFX/JDBC mode; visual parity and centralized deployment have not been accepted.

| Feature | Original Implementation | JavaFX Implementation | Test Status |
| --- | --- | --- | --- |
| Landing page | React static hero, showcase, features, flow, CTAs | Native JavaFX landing; simplified content/layout | Implemented; visual comparison pending |
| Navigation/footer | React Router, Navbar, Footer, responsive drawer | Native JavaFX shell/navigation/footer; desktop layout | Implemented; visual comparison pending |
| Login/session | Django SimpleJWT; React form/localStorage/refresh interceptor | JDBC auth; PBKDF2 hashes; expiring, revocable local session | 2 auth/session tests pass; remote JWT parity pending |
| Registration | React form + Django serializer; USER role forced server-side | JDBC registration; public creation is USER-only | Registration/security tests pass |
| Profile fetch/update | Django `/auth/me/`; frontend auth service | Profile update service exists; no profile editor UI | Service untested; no UI interaction in source |
| Logout | Django refresh blacklist + client state clear | Local session revoke and token-file clear | Session tests pass |
| Authorization | Django permissions and React route guards | Role routing and service-layer staff/admin checks | Queue role path tested partially; UI access test pending |
| Unauthorized screen | React `UnauthorizedPage` | Native JavaFX access-denied view | Implemented; UI test pending |
| Service listing | Django service API and customer/staff/admin views | JDBC DAO + customer/staff/admin native views | Covered indirectly; dedicated view test pending |
| Service CRUD | Django admin-only API; no admin dashboard controls | Not visible in UI; no new controls planned | Not applicable |
| Token generation | Django count-based per-service prefix sequence | JDBC count-based sequence under serialized write transaction | Covered by queue tests |
| Join queue | Django validation + React service card action | Active-service/active-token checks and persisted token | Queue tests pass |
| Active token/status | Django token/status endpoints + React panels | Native status/wait panels | Wait/position covered; UI test pending |
| Live customer updates | React polls token/status every 5 seconds | Background JavaFX polling; history excluded from polling | Implemented; timing/UI test pending |
| Cancel token | Django permits own WAITING/CALLED cancellation | Owner/status-checked JDBC transaction | Cancellation covered |
| Queue history | Django latest 50 terminal tokens; React table | Native table for COMPLETED/SKIPPED/CANCELLED | Implementation present; dedicated test pending |
| Staff call-next | Django FIFO/transaction/select-for-update | SQLite `BEGIN IMMEDIATE` and conditional FIFO claim | Concurrency/FIFO tests pass |
| Staff lifecycle | Django start/complete/skip/recall | Validated JDBC transitions | Lifecycle tests pass |
| Staff overview/live refresh | React overview + 5-second polling | Native overview and background refresh | Implemented; UI/timing test pending |
| Admin health | Django health endpoint; manual React refresh | Local database/runtime indicators; manual refresh | Implementation present; UI test pending |
| Admin analytics | Django aggregates; React summaries/bar visualization | JDBC aggregates and native JavaFX bar charts | Aggregate queries tested; chart UI pending |
| Admin service overview | Authenticated service listing; no CRUD UI | Read-only native table | Implemented; dedicated test pending |
| Loading/error/empty states | React page-level state/messages | JavaFX inline status/errors/empty table states | Partial; UI test pending |
| Demo login | Frontend quick-fill requires seeded backend accounts | Quick-fill + explicit idempotent demo seeder | Seeder idempotency test passes |
| Data migration | Django ORM schema; no DB file supplied | Schema aligned to migrations; no source DB imported | Blocked: database unavailable |
| Packaging | Web deploy and separate frontend/backend start | Maven run/build; jpackage setup not completed | Pending |

**Global blocker:** the implemented application is a standalone local SQLite installation, not yet a multi-terminal client/server replacement for Render/Django.