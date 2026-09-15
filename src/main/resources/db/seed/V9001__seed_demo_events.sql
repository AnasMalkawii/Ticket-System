-- Seed data for local development and load tests. NOT applied in production.
--
-- Lives in classpath:db/seed, a Flyway location added only by the `local` and `test`
-- profiles. Production runs classpath:db/migration alone, so this file can never create
-- fixture rows in a real environment.
--
-- IDs are fixed so k6 scripts, integration tests, and manual curl calls can hardcode
-- them. Sale windows are relative to now() so the fixture never goes stale.

-- The hot event: exactly 100 tickets, sale already open.
-- This is the fixture for every zero-oversell proof in the project.
INSERT INTO event (id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                   status, price_minor, currency)
VALUES ('11111111-1111-4111-8111-111111111111',
        'Aurora Live - Opening Night',
        'Riverside Arena',
        now() + INTERVAL '30 days',
        now() - INTERVAL '1 hour',
        now() + INTERVAL '29 days',
        'ON_SALE', 4500, 'EUR');

INSERT INTO ticket_inventory (event_id, total, available, held, sold)
VALUES ('11111111-1111-4111-8111-111111111111', 100, 100, 0, 0);

-- Sale has not started yet: fixture for the SALE_NOT_STARTED rejection path.
INSERT INTO event (id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                   status, price_minor, currency)
VALUES ('22222222-2222-4222-8222-222222222222',
        'Aurora Live - Second Night',
        'Riverside Arena',
        now() + INTERVAL '31 days',
        now() + INTERVAL '7 days',
        now() + INTERVAL '30 days',
        'SCHEDULED', 4500, 'EUR');

INSERT INTO ticket_inventory (event_id, total, available, held, sold)
VALUES ('22222222-2222-4222-8222-222222222222', 250, 250, 0, 0);

-- Sale window closed: fixture for the SALE_ENDED rejection path.
INSERT INTO event (id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                   status, price_minor, currency)
VALUES ('33333333-3333-4333-8333-333333333333',
        'Midnight Sessions - Finale',
        'The Vault',
        now() + INTERVAL '2 days',
        now() - INTERVAL '30 days',
        now() - INTERVAL '1 day',
        'CLOSED', 3000, 'EUR');

INSERT INTO ticket_inventory (event_id, total, available, held, sold)
VALUES ('33333333-3333-4333-8333-333333333333', 50, 50, 0, 0);
