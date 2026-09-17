CREATE TABLE app_user (
    id                    UUID PRIMARY KEY,
    username              VARCHAR(64) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(16) NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_username_lowercase CHECK (username = lower(username)),
    CONSTRAINT ck_app_user_username_format CHECK (username ~ '^[a-z0-9._-]{3,64}$'),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_app_user_failed_attempts CHECK (failed_login_attempts >= 0)
);

CREATE UNIQUE INDEX uq_app_user_username ON app_user (username);

CREATE TABLE auth_session (
    id          UUID PRIMARY KEY,
    family_id   UUID NOT NULL,
    user_id     UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    csrf_hash   VARCHAR(64) NOT NULL,
    client_ip   VARCHAR(45),
    user_agent  VARCHAR(512),
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID REFERENCES auth_session(id),
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_auth_session_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_session_replacement CHECK (replaced_by IS NULL OR replaced_by <> id)
);

CREATE INDEX ix_auth_session_user ON auth_session (user_id);
CREATE INDEX ix_auth_session_family ON auth_session (family_id);
CREATE INDEX ix_auth_session_expiry ON auth_session (expires_at);
