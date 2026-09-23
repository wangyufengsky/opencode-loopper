CREATE TABLE source_template_result_read (
    model_id TEXT NOT NULL REFERENCES source_template_model(id),
    result_id TEXT NOT NULL REFERENCES source_template_model(id),
    sha256 TEXT NOT NULL,
    part INTEGER NOT NULL CHECK(part >= 0),
    created_at TEXT NOT NULL,
    PRIMARY KEY(model_id,result_id,sha256,part)
);
