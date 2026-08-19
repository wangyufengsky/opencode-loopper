ALTER TABLE stage ADD COLUMN role_pack_id TEXT;
ALTER TABLE stage ADD COLUMN role_pack_version TEXT;
ALTER TABLE stage ADD COLUMN test_policy TEXT;
ALTER TABLE stage ADD COLUMN technologies_json TEXT NOT NULL DEFAULT '[]';

