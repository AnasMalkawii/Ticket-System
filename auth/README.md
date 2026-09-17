# Original authentication project

The standalone project in `auth/auth` is the source reference supplied for the integration.
Ticket System runs the adapted implementation in `src/main/java/com/ticketsystem/auth` and
`src/main/java/com/ticketsystem/user`, built by the root `pom.xml`.

Use the root application and `mvn verify` for the integrated system. The standalone project's
configuration, database, and tests do not configure or test Ticket System.
See `../docs/authentication.md` for the integrated filter chain, credentials, and migration.
