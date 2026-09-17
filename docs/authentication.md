# Authentication

Ticket System uses the supplied auth module, adapted under `src/main/java/com/ticketsystem/auth`
and `src/main/java/com/ticketsystem/user`. It runs inside the same Spring Boot application and
database. The original `auth/auth` standalone project is a reference copy, not a second running
service or part of the main build.

## Filter and identity

`auth/config/SecurityConfig.java` defines the only Spring Security filter chain.
`auth/filter/JwtAuthenticationFilter.java` runs before `UsernamePasswordAuthenticationFilter`.
It extracts the Authorization bearer token, verifies its signature and required claims through
`JwtService -> TokenFactory -> AccessTokenStrategy`, then loads its session and user from PostgreSQL.
It installs a small `AuthenticatedUser` principal with the UUID and the current database role.
The filter is registered only in Spring Security, avoiding duplicate servlet registration.

The route rules keep catalog GETs public, restrict event mutations to ADMIN, restrict booking
writes to USER, and deny unmatched routes. Reservation services additionally check ownership;
administrators may read another user's reservation but cannot cancel it. Admin controllers also
use method authorization. No request header or body field supplies the acting user ID.

## Credentials and lifetimes

- Passwords use adaptive BCrypt with Spring's `{bcrypt}` format, preserving existing hashes.
- Access tokens are HS256 JWTs valid for **15 minutes** by default.
- Refresh tokens are HS256 JWTs valid for **7 days** by default and rotate after each use.
- Access and refresh tokens use separate signing secrets and required `type` claims. Validation
  also requires the issuer, audience, UUID subject, token ID, session ID, issued-at, not-before,
  and expiration claims.
- Refresh tokens carry a session identifier but only their SHA-256 hashes are stored.
- Each refresh issues a new pair with a new 7-day expiry; this is a rolling lifetime, not a
  7-day absolute limit measured from the first login.
- Logout, refresh replay, and disabled accounts invalidate the affected session immediately.
  Roles are read from the database on each request.
- Login lockout blocks password login; it does not let failed password guesses terminate an
  already authenticated user's valid session.

The benchmark-only `load` profile retains its 1-hour access / 2-hour refresh overrides for
long-running k6 scenarios. Normal application and integration-test profiles use 15 minutes / 7 days.

## Required runtime secrets

Supply `DB_USERNAME`, `DB_PASSWORD`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`,
`JWT_ACCESS_SECRET`, and `JWT_REFRESH_SECRET` through environment variables.
The two JWT secrets must be different Base64 values, each encoding at least 32 random bytes.
All replicas must receive the same pair. There are no production key defaults.
The old `JWT_SIGNING_KEY` setting is no longer used.

To generate the pair in a PowerShell process without printing the secrets:

```powershell
$authKeyBytes = [byte[]]::new(48)
[Security.Cryptography.RandomNumberGenerator]::Fill($authKeyBytes)
$env:JWT_ACCESS_SECRET = [Convert]::ToBase64String($authKeyBytes)
[Security.Cryptography.RandomNumberGenerator]::Fill($authKeyBytes)
$env:JWT_REFRESH_SECRET = [Convert]::ToBase64String($authKeyBytes)
```

Persist deployment values in your secret store. Rotating the access key invalidates access tokens;
a valid refresh token can obtain a replacement. Rotating the refresh key requires users to log in again.

## Browser client flow

1. Register with `POST /api/v1/auth/register`, then log in with `POST /api/v1/auth/login`.
2. Keep the response's `accessToken` in memory and send `Authorization: Bearer <token>`.
   Responses retain the existing `tokenType`, `expiresIn` (900 by default), and `role` fields.
3. Login sets a Secure, HttpOnly `__Host-ticket_refresh` cookie and a readable
   `__Host-ticket_csrf` cookie. Both have configurable names for local HTTP development.
4. For `POST /api/v1/auth/refresh` or `POST /api/v1/auth/logout`, copy the CSRF cookie into
   the `X-CSRF-TOKEN` header. The server checks both values against the session's stored hash.
5. Replace the access token and cookies after refreshing. Serialize refresh requests in the
   client: simultaneous uses of one refresh token are treated as replay, revoking that login's family.
6. Logout returns 204 and clears both cookies. `GET /api/v1/auth/me` returns the current user.

Refresh and logout accept the refresh credential only through its cookie. Cookie `SameSite`
is additional protection; the CSRF check remains required. These four public authentication
POSTs ignore any stale Authorization header, so an expired access token cannot block refresh.
Other protected routes always require a valid bearer token. Set `AUTH_ALLOWED_ORIGINS` to exact
browser origins and keep `AUTH_COOKIE_SECURE=true` with HTTPS. Use explicit non-prefixed cookie
names and `secure=false` only in isolated HTTP development.

## Migration and administrator setup

Flyway migration V10 revokes old sessions. Existing `app_user` UUIDs, password hashes, roles,
and reservation ownership are preserved; users must log in again after this replacement.
The adapted `User` maps to `app_user`, and `RefreshToken` maps to `auth_session`.
No Hibernate schema mutation is required.

Optionally set `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD_HASH` on first boot.
The latter must be a `{bcrypt}` hash with work factor at least 10. Remove these bootstrap
settings afterward. Existing normal accounts cannot be promoted by this bootstrap.
The `local` profile seeds demo users; production never runs demo seed migrations.
Local/load/test profiles permit out-of-order migrations because their seed versions (9000+)
sort after normal schema migrations.

## Tests and deployment expectations

Run `mvn verify` with Docker available. Integration tests use real PostgreSQL and signed bearer
tokens through the custom filter; they cover admin routes, ownership, malformed/expired/wrong-type
tokens, refresh concurrency and replay, disabled users, logout, CSRF, and database role changes.

If this Windows JDK reports `Unable to establish loopback connection`, the verified workaround
is to clear TEMP/TMP only in a child test process (no persistent environment changes):

```powershell
powershell -NoProfile -Command 'Remove-Item -LiteralPath Env:TEMP,Env:TMP -ErrorAction SilentlyContinue; mvn verify'
```

PostgreSQL is needed for authenticated requests. The login throttle is bounded per process,
with account locks stored in PostgreSQL. A deployment with multiple replicas should apply a
shared edge rate limit and configure trusted proxy address handling. Authentication responses
use `Cache-Control: no-store`, and expired session records are cleaned up periodically.
