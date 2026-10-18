-- Version of the proprietary data of each pokemon, changed only by local data updates (never by the sync).
-- It backs the ETag of the detailed view and the optional If-Match check on updates.

ALTER TABLE pokemon
    ADD COLUMN local_data_version     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN local_data_modified_at TIMESTAMPTZ;

-- Last line of defence for the note length limit enforced by the API.
ALTER TABLE pokemon_geo_metadata
    ADD CONSTRAINT pokemon_geo_metadata_note_length CHECK (char_length(note) <= 1000);
