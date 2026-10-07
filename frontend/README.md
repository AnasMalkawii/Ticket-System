# Encore — Ticket System frontend

A responsive React app for the existing Spring Boot API, built with Vite, strict TypeScript, Tailwind CSS, React Router, TanStack Query, native Fetch, and Zod. Production output is a static site; proxy API requests to Spring Boot.

## Run locally

Use **Node.js 22.12+ or 24+** and npm. This computer's Node 22.11 starts the frontend but displays Vite's version warning; upgrade it for a supported runtime. Dependencies are locked in `package-lock.json`.

In `frontend`, install dependencies once with `npm ci`. Then start the frontend with either `npm start` or `npm run dev`.

Open [localhost:5173](http://localhost:5173). Development defaults forward `/api` to `http://localhost:8080` and use the backend's `ticket_csrf` cookie. No frontend environment file is required. Optional API/proxy/cookie overrides can go in `.env.local`; restart Vite after changing them.

Open Docker Desktop and run `TicketSystemApplication` in IntelliJ from the repository root. The backend automatically loads the private root `application.properties`, starts PostgreSQL, Redis, and RabbitMQ, and applies local HTTP cookie settings. See [the root startup instructions](../README.md#run-locally). The file is already configured here; on another computer, copy its example and supply the secrets once.

Use `localhost` consistently because cookies belong to the browser hostname. The backend's `local` profile seeds demo accounts and events. Real events and bookings require a running backend; there is no automatic demo-data fallback.

## Pages

| Route                          | Purpose                                                   |
| ------------------------------ | --------------------------------------------------------- |
| `/`                            | Paginated catalog and sale-status filters                 |
| `/events/:eventId`             | Details, availability, quantity selection, reservation    |
| `/register`, `/login`          | Validated account creation and sign-in                    |
| `/tickets`                     | Locally remembered reservations and reservation-ID lookup |
| `/reservations/:reservationId` | Live countdown, confirmation, cancellation, status        |
| `/admin`                       | Administrator event catalog                               |
| `/admin/events/new`            | Event creation and initial inventory                      |
| `/admin/events/:eventId`       | Edit details, sale status, and add tickets                |

Booking requires USER; event management requires ADMIN. Frontend guards help navigation; Spring Security enforces authorization.

Current API limitations:

- **No reservation-list endpoint:** up to 100 reservation IDs per user are remembered in local storage; their state is fetched from the server. Other devices need lookup by ID. Clearing browser storage clears the list, not server bookings.
- **No order retrieval endpoint:** the confirmation response’s order receipt is cached during the visit. Reloading retains the reservation’s server status.
- **Mock payment:** confirmation uses the backend’s `tok_ok` simulation. The page explains that no real payment or card collection occurs. Real checkout requires a backend payment integration.
- Quantity validation matches the backend’s current default of 4. Update `quantitySchema`, the picker, and help text together if you change backend limits.
- Images, categories, and descriptions are not in the API. Local poster artwork uses existing event metadata.
- PATCH cannot clear a saved optional date. Leaving it blank preserves its previous value.

## Organization

```text
src/
  auth/          Session provider and auth hook
  components/    Layout, poster artwork, small UI components
  lib/           Fetch, queries, types, validation, formatting, storage
  pages/         Route components
  App.tsx        Routes and access guards
  main.tsx       App and Query providers
  index.css      Tailwind, theme, shared styles, responsive layouts
tests/
  unit/          API authentication and validation boundaries
  e2e/           Browser flows with controlled API fixtures
```

Forms use native form state and Zod; TanStack Query owns remote state. Access tokens stay in memory; refresh credentials stay in the backend’s HttpOnly cookie. Refresh/logout send the CSRF cookie value in `X-CSRF-TOKEN`. Refreshes are serialized within a tab and across tabs using Web Locks where available. Sign-out clears private queries; a failed sign-out remains visible for retry.

Every request includes `X-Request-Id`. Reservation writes retain their `Idempotency-Key` after uncertain results, including reloads. Mutations do not automatically retry network failures. Problem Details codes produce readable errors with support references. Queries support cancellation; Fetch requests have deadlines.

## Verify

```powershell
npm run build
npm run lint
npm run format:check
npm test
npx playwright install chromium
npm run test:e2e
```

To use already-installed Chrome instead of downloading Chromium:

```powershell
$env:PLAYWRIGHT_CHANNEL = 'chrome'
npm run test:e2e
```

Browser tests cover desktop/mobile catalogs, validation, roles, sign-out, reservations, confirmation, cancellation, session restoration, uncertain retries, admin forms, and API failures. Fixtures exist **only in tests** and do not replace a live backend integration test.

## Deploy later

```powershell
npm ci
npm run build
```

Deploy `dist/`. Your web server must serve `index.html` for application routes, return 404 for missing assets, and forward `/api/` to Spring Boot. Vite’s dev proxy is not included in production. `VITE_` values are public build-time settings; never put secrets in them. `npm run preview` is a local build preview with no API proxy.

**Use one HTTPS origin for the frontend and API.** For example, the app at `https://tickets.example.com/` forwards `/api/` to Spring Boot. The frontend must be able to read the CSRF cookie, so a directly addressed API on an unrelated hostname is not supported by this cookie flow. Set backend `AUTH_ALLOWED_ORIGINS` to the exact public frontend origin. Keep Secure `__Host-` cookies and the default CSRF name in production. Do not use local HTTP cookie settings or pass `.env.local` into a production build.

The included Nginx Docker setup handles API forwarding, SPA routes, and caching for hashed assets. From the repository root:

```powershell
docker build -t ticket-system-frontend ./frontend
docker run --rm -p 3000:80 --network YOUR_BACKEND_NETWORK -e API_UPSTREAM=http://YOUR_BACKEND_SERVICE:8080 ticket-system-frontend
```

Replace the network and backend service name with your deployment values. `API_UPSTREAM` must be a reachable origin without a trailing path. Terminate HTTPS at your ingress/load balancer; plain HTTP on port 3000 does not support production Secure cookies. The API location never caches responses.

References: [Vite](https://vite.dev/guide/), [Tailwind](https://tailwindcss.com/docs/installation/using-vite), [React Router](https://reactrouter.com/start/declarative/installation), [TanStack Query](https://tanstack.com/query/latest/docs/framework/react/overview), [Zod](https://zod.dev/basics).
