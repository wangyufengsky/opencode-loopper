CREATE TABLE design_acceptance_planning (
    compilation_id TEXT PRIMARY KEY REFERENCES loop_spec_compilation(id) ON DELETE CASCADE,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    work_package_id TEXT NOT NULL,
    design_revision INTEGER NOT NULL CHECK (design_revision > 0),
    contract_version TEXT NOT NULL,
    design_sha256 TEXT NOT NULL CHECK (length(design_sha256) = 64),
    state TEXT NOT NULL CHECK (state IN ('EXTRACTED', 'BOUND', 'COMPILED', 'FAILED')),
    facts_json TEXT NOT NULL,
    capabilities_json TEXT NOT NULL,
    binding_json TEXT,
    diagnostics_json TEXT,
    error_code TEXT,
    error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_design_acceptance_planning_package
    ON design_acceptance_planning(designer_session_id, work_package_id, design_revision DESC);

CREATE TRIGGER trg_design_acceptance_planning_identity_immutable
BEFORE UPDATE OF compilation_id, designer_session_id, work_package_id, design_revision,
                 contract_version, design_sha256, facts_json, capabilities_json
ON design_acceptance_planning
BEGIN
    SELECT RAISE(ABORT, 'design acceptance planning identity is immutable');
END;
