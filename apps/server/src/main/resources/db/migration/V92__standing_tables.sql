-- V92: Durable private table codes and the players who return to them.
CREATE TABLE standing_tables (
    code TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 32),
    buy_in BIGINT NOT NULL,
    max_seats INT NOT NULL,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    local_time TIME NOT NULL,
    time_zone TEXT NOT NULL,
    duration_minutes INT NOT NULL DEFAULT 120 CHECK (duration_minutes BETWEEN 30 AND 480),
    felt_product_id TEXT,
    card_back_product_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ
);
CREATE INDEX standing_tables_owner_active ON standing_tables (owner_user_id) WHERE archived_at IS NULL;
CREATE TABLE standing_table_regulars (
    code TEXT NOT NULL REFERENCES standing_tables(code) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    first_joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (code, user_id)
);
CREATE INDEX standing_table_regulars_user ON standing_table_regulars (user_id);
ALTER TABLE standing_tables ENABLE ROW LEVEL SECURITY;
ALTER TABLE standing_table_regulars ENABLE ROW LEVEL SECURITY;
