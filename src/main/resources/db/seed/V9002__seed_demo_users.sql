INSERT INTO app_user (id, username, password_hash, role, enabled, created_at, updated_at)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'test-user',
     '{bcrypt}$2a$10$7pBroRcpLXkenvodt2o3COm5.QkwUqaoPem0B8J.eFHnKtIK6LS.m',
     'USER', TRUE, now(), now()),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'test-admin',
     '{bcrypt}$2a$10$PHHLlB8zIP3JnPn1ulTXG.20oxzflq7po.Q365o3pFhYEBfxYoXwy',
     'ADMIN', TRUE, now(), now())
ON CONFLICT DO NOTHING;
