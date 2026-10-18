-- Users of the auxiliary auth API, their roles, and their sign-in sessions.

CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE CHECK (username = lower(username)),
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE app_user_role (
    user_id BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role    VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

-- One row per sign-in. Ending it (sign-out, refresh token reuse) invalidates its access and refresh tokens.
-- token_generation goes up on every refresh; access tokens carry it, so older ones stop working at once.
CREATE TABLE auth_session (
    id               UUID        PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_generation INTEGER     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    end_reason       VARCHAR(20) CHECK (end_reason IN ('LOGOUT', 'REUSE'))
);

CREATE INDEX auth_session_user_idx ON auth_session (user_id);

-- Only a SHA-256 hash of each refresh token is stored. Used tokens are kept (used_at set) so a second use
-- can be recognised as reuse.
CREATE TABLE auth_refresh_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id UUID        NOT NULL REFERENCES auth_session (id) ON DELETE CASCADE,
    generation INTEGER     NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

CREATE INDEX auth_refresh_token_session_idx ON auth_refresh_token (session_id);
