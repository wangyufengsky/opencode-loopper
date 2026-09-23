ALTER TABLE ppt_generation ADD COLUMN requirements_confirmed INTEGER NOT NULL DEFAULT 0
    CHECK (requirements_confirmed IN (0,1));
