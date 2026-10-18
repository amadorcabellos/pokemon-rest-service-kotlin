-- Upstream data (owned by PokeAPI, written by the sync and the lookup).

ALTER TABLE pokemon
    ADD COLUMN species_id     INTEGER,
    ADD COLUMN species_name   VARCHAR(100),
    ADD COLUMN last_synced_at TIMESTAMPTZ;

CREATE TABLE pokemon_stat (
    pokemon_id BIGINT      NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    slot       INTEGER     NOT NULL,
    stat_name  VARCHAR(50) NOT NULL,
    base_value INTEGER     NOT NULL,
    PRIMARY KEY (pokemon_id, slot)
);

CREATE TABLE pokemon_ability (
    pokemon_id   BIGINT       NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    slot         INTEGER      NOT NULL,
    ability_name VARCHAR(100) NOT NULL,
    hidden       BOOLEAN      NOT NULL,
    PRIMARY KEY (pokemon_id, slot)
);

-- Proprietary data (owned by this service, never written by the sync).

CREATE TABLE pokemon_localized_name (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pokemon_id BIGINT       NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    locale     VARCHAR(35)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    UNIQUE (pokemon_id, locale)
);

CREATE TABLE pokemon_geo_metadata (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pokemon_id BIGINT       NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    kind       VARCHAR(20)  NOT NULL CHECK (kind IN ('REGION', 'AREA', 'HABITAT')),
    value      VARCHAR(100) NOT NULL,
    note       TEXT,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX pokemon_geo_metadata_pokemon_idx ON pokemon_geo_metadata (pokemon_id);

CREATE TABLE pokemon_tag (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pokemon_id BIGINT      NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    tag        VARCHAR(50) NOT NULL CHECK (tag = lower(tag)),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (pokemon_id, tag)
);

-- Full synchronization runs.

CREATE TABLE sync_run (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    triggered_by    VARCHAR(20) NOT NULL,
    status          VARCHAR(30) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    catalogue_count INTEGER     NOT NULL DEFAULT 0,
    created_count   INTEGER     NOT NULL DEFAULT 0,
    updated_count   INTEGER     NOT NULL DEFAULT 0,
    failed_count    INTEGER     NOT NULL DEFAULT 0
);

-- At most one full synchronization can be running at any time.
CREATE UNIQUE INDEX sync_run_one_running ON sync_run (status) WHERE status = 'RUNNING';

CREATE TABLE sync_run_failure (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sync_run_id  BIGINT       NOT NULL REFERENCES sync_run (id) ON DELETE CASCADE,
    pokemon_name VARCHAR(100) NOT NULL,
    reason       VARCHAR(500) NOT NULL
);

CREATE INDEX sync_run_failure_run_idx ON sync_run_failure (sync_run_id);
