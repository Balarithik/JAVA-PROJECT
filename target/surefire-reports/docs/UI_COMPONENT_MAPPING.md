# UI Component Mapping

| Existing React component/view | Current appearance and interactions | JavaFX mapping | Status |
| --- | --- | --- | --- |
| `App.jsx` | Shared header/footer, route switch, protected route handling | `BorderPane` shell plus centralized controller navigation | Implemented; no route-level UI tests |
| `Navbar.jsx` | 64px dark blurred header; brand; links; role badge; login/register or portal/logout; mobile drawer | Native JavaFX header; desktop menu omits mobile drawer | Partial; visual comparison pending |
| `Footer.jsx` | Dark footer with brand, portal links, tech highlights, status claim | Native JavaFX footer | Partial; visual comparison pending |
| `LandingPage.jsx` | Dark hero and static showcase, feature grid, steps, CTA | Native JavaFX hero and workflow cards; content simplified | Partial; screenshot comparison pending |
| `LoginPage.jsx` | Centered glass form; email/password; visibility toggle; expired/error states; demo buttons | Native JavaFX form, validation, visibility, explicit seeded demo shortcuts | Implemented; UI test pending |
| `RegisterPage.jsx` | Glass form, name/email/phone/password/confirm, role selector | Native JavaFX customer-only form; privileged role selector removed | Implemented; registration test passes |
| `UserDashboard.jsx` | Light-gray background, token/status/service cards, join/cancel, history | Separate light-theme JavaFX layout, cards, `TableView` | Implemented; screenshot comparison pending |
| `StaffDashboard.jsx` | Dark glass, service selection, metrics, current token, five controls, overview | Dark JavaFX CSS, `ComboBox`, metrics, buttons, `TableView` | Implemented; lifecycle tests pass |
| `AdminDashboard.jsx` | Dark glass, manual refresh, health metrics, analytics, service table | Dark JavaFX CSS, native `BarChart`s and `TableView` | Implemented; analytics tests pending |
| `UnauthorizedPage.jsx` | Centered rose alert and dashboard/home links | JavaFX access-denied view | Implemented; UI test pending |
| Loading/errors/empty | Inline labels, banners, disabled controls, loading text | `ProgressIndicator`, status labels, `Alert`/inline banner; preserve per-view distinction | Pending |

## Styling Reference

Dark pages use `#0b0f19`, slate surfaces/borders, indigo/cyan gradients, emerald success, amber warning, rose errors, rounded glass cards/inputs, shadows, and interaction states. `UserDashboard.jsx` intentionally uses a light gray/white palette. JavaFX CSS must retain this page-specific split. The original uses Tailwind's sans stack and Lucide icons; exact font assets are not present in the inspected source, so font and icon equivalence need visual validation.

## Interaction Notes

- Registration's visible STAFF/ADMIN selection is not honored by the backend and cannot be carried forward as a security capability.
- Login's “Forgot?” text has no action in the original implementation.
- Landing-page queue values are static display content, not live API information.
- Service create/update/delete exist in Django API but are not surfaced in the admin React dashboard; do not add these controls as supposed parity.
- Browser responsiveness maps to resizable desktop layouts, but a desktop-only JavaFX window has no mobile viewport to reproduce.