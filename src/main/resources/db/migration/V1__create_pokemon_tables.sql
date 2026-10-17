CREATE TABLE pokemon (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poke_api_id     INTEGER      NOT NULL UNIQUE,
    name            VARCHAR(100) NOT NULL UNIQUE,
    height          INTEGER,
    weight          INTEGER,
    base_experience INTEGER,
    sprite_url      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE pokemon_type (
    pokemon_id BIGINT      NOT NULL REFERENCES pokemon (id) ON DELETE CASCADE,
    slot       INTEGER     NOT NULL,
    type_name  VARCHAR(50) NOT NULL,
    PRIMARY KEY (pokemon_id, slot)
);
