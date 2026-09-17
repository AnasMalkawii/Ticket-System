INSERT INTO app_user (id, username, password_hash, role, enabled, created_at, updated_at)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'load-user',
     '{bcrypt}$2a$10$E48nxbwy9iQvsYkKValg6ectOZrSx/ZCaZRBcEh5LTZIsUY9mD0uO',
     'USER', TRUE, now(), now()),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'load-admin',
     '{bcrypt}$2a$10$E48nxbwy9iQvsYkKValg6ectOZrSx/ZCaZRBcEh5LTZIsUY9mD0uO',
     'ADMIN', TRUE, now(), now())
ON CONFLICT DO NOTHING;
