-- Durable launch saga for the Acceptance v7 internal-MCP candidate Session.
-- PREPARED freezes every owner/source/planning and remote-create input before
-- external I/O. SETTLED transfers the live remote to the candidate run; failure
-- terminals require positive remote-stop proof instead.
CREATE TABLE acceptance_candidate_internal_launch (
    id TEXT PRIMARY KEY,
    compilation_id TEXT NOT NULL UNIQUE REFERENCES loop_spec_compilation(id) ON DELETE CASCADE,
    designer_session_id TEXT NOT NULL REFERENCES designer_session(id) ON DELETE CASCADE,
    work_package_id TEXT NOT NULL,
    source_design_revision INTEGER NOT NULL CHECK (source_design_revision > 0),
    source_design_message_id TEXT NOT NULL REFERENCES designer_message(id),
    source_draft_version INTEGER NOT NULL CHECK (source_draft_version >= 0),
    source_design_sha256 TEXT NOT NULL CHECK (length(source_design_sha256)=64),
    planning_version INTEGER NOT NULL CHECK (planning_version >= 0),
    planning_binding_source TEXT NOT NULL CHECK (planning_binding_source='AI_DISAMBIGUATION_V6'),
    planning_binding_json TEXT NOT NULL CHECK (
      json_valid(planning_binding_json) AND json_type(planning_binding_json)='object'),
    planning_binding_sha256 TEXT NOT NULL CHECK (length(planning_binding_sha256)=64),
    route_plan_json TEXT NOT NULL CHECK (
      json_valid(route_plan_json) AND json_type(route_plan_json)='object'),
    route_plan_sha256 TEXT NOT NULL CHECK (length(route_plan_sha256)=64),
    candidate_run_id TEXT NOT NULL UNIQUE,
    contract_version TEXT NOT NULL CHECK (contract_version='ACCEPTANCE_CLOSED_CHOICE_V7'),
    workflow_step TEXT NOT NULL CHECK (workflow_step='ACCEPTANCE_CLOSED_CHOICE_V7'),
    state TEXT NOT NULL CHECK (state IN (
      'PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING',
      'SETTLED','FAILED_STOPPED','CANCELLED','STALE')),
    prepared_owner_version INTEGER NOT NULL CHECK (prepared_owner_version >= 0),
    settled_owner_version INTEGER CHECK (settled_owner_version >= 0),
    settled_at TEXT,
    exact_title TEXT NOT NULL UNIQUE CHECK (length(trim(exact_title)) > 0),
    canonical_directory TEXT NOT NULL CHECK (length(trim(canonical_directory)) > 0),
    runtime_generation_id TEXT NOT NULL CHECK (length(trim(runtime_generation_id)) > 0),
    managed INTEGER NOT NULL CHECK (managed=1),
    internal_mcp_server TEXT NOT NULL CHECK (length(trim(internal_mcp_server)) > 0),
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint)=64),
    model_provider_id TEXT,
    model_id TEXT,
    thinking INTEGER CHECK (thinking IS NULL OR thinking IN (0,1)),
    profile TEXT NOT NULL CHECK (profile='ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS'),
    permission_policy_json TEXT NOT NULL CHECK (
      json_valid(permission_policy_json) AND json_type(permission_policy_json)='array'),
    permission_policy_digest TEXT NOT NULL CHECK (length(permission_policy_digest)=64),
    create_request_sha256 TEXT NOT NULL CHECK (length(create_request_sha256)=64),
    creation_credential TEXT NOT NULL UNIQUE CHECK (
      length(creation_credential)=43 AND creation_credential NOT GLOB '*[^A-Za-z0-9_-]*'),
    attestation_type TEXT NOT NULL CHECK (attestation_type='LOCAL_REQUEST_ATTESTED'),
    create_claim_owner TEXT,
    create_claim_token TEXT,
    create_claim_expires_at TEXT,
    create_fence INTEGER NOT NULL DEFAULT 0 CHECK (create_fence >= 0),
    create_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (create_dispatch_attempted IN (0,1)),
    create_dispatch_started_at TEXT,
    external_session_id TEXT,
    external_attested_at TEXT,
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    failure_phase TEXT CHECK (failure_phase IS NULL OR failure_phase IN (
      'CREATE_LOOKUP','CREATE_POST','REMOTE_ATTESTATION','REMOTE_STOP','OWNER_REVALIDATION','SETTLEMENT')),
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((model_provider_id IS NULL)=(model_id IS NULL)),
    CHECK ((settled_owner_version IS NULL)=(settled_at IS NULL)),
    CHECK ((external_session_id IS NULL)=(external_attested_at IS NULL)),
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_token IS NULL)),
    CHECK ((create_claim_owner IS NULL)=(create_claim_expires_at IS NULL)),
    CHECK ((create_dispatch_attempted=0 AND create_dispatch_started_at IS NULL)
      OR (create_dispatch_attempted=1 AND create_dispatch_started_at IS NOT NULL)),
    CHECK (external_session_id IS NULL OR create_dispatch_attempted=1),
    CHECK (termination_proof IS NULL OR external_session_id IS NOT NULL),
    CHECK (create_claim_owner IS NULL OR state IN ('PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING')),
    CHECK (state<>'PREPARED' OR (create_dispatch_attempted=0 AND external_session_id IS NULL
      AND settled_owner_version IS NULL AND termination_proof IS NULL)),
    CHECK (state<>'CREATING' OR (create_dispatch_attempted=1 AND create_claim_owner IS NOT NULL)),
    CHECK (state<>'CREATED' OR (create_dispatch_attempted=1 AND create_claim_owner IS NOT NULL
      AND external_session_id IS NOT NULL)),
    CHECK (state<>'SETTLED' OR (external_session_id IS NOT NULL
      AND settled_owner_version=prepared_owner_version+1 AND termination_proof IS NULL
      AND create_claim_owner IS NULL)),
    CHECK (state='SETTLED' OR settled_owner_version IS NULL),
    CHECK (state NOT IN ('FAILED_STOPPED','CANCELLED','STALE')
      OR external_session_id IS NULL OR termination_proof IS NOT NULL),
    CHECK (termination_proof IS NULL OR state IN ('FAILED_STOPPED','CANCELLED','STALE')),
    CHECK (state NOT IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE')
      OR create_claim_owner IS NULL)
);

CREATE UNIQUE INDEX ux_acceptance_internal_launch_remote
    ON acceptance_candidate_internal_launch(external_session_id)
    WHERE external_session_id IS NOT NULL;
CREATE UNIQUE INDEX ux_acceptance_internal_launch_active_package
    ON acceptance_candidate_internal_launch(designer_session_id,work_package_id)
    WHERE state IN ('PREPARED','CREATING','CREATED','DISCONNECTED','STOPPING');
CREATE INDEX idx_acceptance_internal_launch_recovery
    ON acceptance_candidate_internal_launch(state,updated_at,id);

CREATE TRIGGER acceptance_internal_launch_owner_source_planning_insert
BEFORE INSERT ON acceptance_candidate_internal_launch
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1
    FROM loop_spec_compilation compilation
    JOIN designer_message message
      ON message.id=NEW.source_design_message_id
     AND message.designer_session_id=NEW.designer_session_id
    JOIN design_work_package work_package
      ON work_package.designer_session_id=NEW.designer_session_id
     AND work_package.package_id=NEW.work_package_id
     AND work_package.design_revision=NEW.source_design_revision
     AND work_package.design_message_id=NEW.source_design_message_id
     AND work_package.id=(
       SELECT current_work_package.id FROM design_work_package current_work_package
       WHERE current_work_package.designer_session_id=NEW.designer_session_id
         AND current_work_package.package_id=NEW.work_package_id
       ORDER BY current_work_package.plan_revision DESC,current_work_package.created_at DESC LIMIT 1)
    JOIN design_requirement_revision revision
      ON revision.id=work_package.requirement_revision_id
     AND revision.designer_session_id=NEW.designer_session_id
     AND revision.source_draft_version=NEW.source_draft_version
    JOIN design_acceptance_planning planning
      ON planning.compilation_id=compilation.id
     AND planning.designer_session_id=NEW.designer_session_id
     AND planning.work_package_id=NEW.work_package_id
     AND planning.design_revision=NEW.source_design_revision
     AND planning.contract_version='DESIGN_ACCEPTANCE_V7'
     AND planning.design_sha256=NEW.source_design_sha256
     AND planning.version=NEW.planning_version
     AND planning.state='EXTRACTED'
     AND planning.binding_source=NEW.planning_binding_source
     AND planning.binding_json=NEW.planning_binding_json
    WHERE compilation.id=NEW.compilation_id
      AND compilation.designer_session_id=NEW.designer_session_id
      AND compilation.work_package_id=NEW.work_package_id
      AND compilation.design_revision=NEW.source_design_revision
      AND compilation.source_design_message_id=NEW.source_design_message_id
      AND compilation.source_draft_version=NEW.source_draft_version
      AND compilation.state='PENDING_HANDOFF'
      AND compilation.version=NEW.prepared_owner_version
      AND compilation.external_session_id IS NULL
  ) THEN RAISE(ABORT,'acceptance internal launch owner/source/planning anchor mismatch') END;

  SELECT CASE WHEN NEW.external_session_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM open_code_session_runtime_binding binding
    WHERE binding.external_session_id=NEW.external_session_id
      AND binding.runtime_generation_id=NEW.runtime_generation_id
      AND binding.ownership_mode='MANAGED'
      AND binding.endpoint_fingerprint=NEW.endpoint_fingerprint
      AND binding.internal_mcp_server=NEW.internal_mcp_server
  ) THEN RAISE(ABORT,'acceptance internal launch remote attestation mismatch') END;
END;

CREATE TRIGGER acceptance_internal_launch_identity_plan_immutable
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN NEW.id<>OLD.id
  OR NEW.compilation_id<>OLD.compilation_id
  OR NEW.designer_session_id<>OLD.designer_session_id
  OR NEW.work_package_id<>OLD.work_package_id
  OR NEW.source_design_revision<>OLD.source_design_revision
  OR NEW.source_design_message_id<>OLD.source_design_message_id
  OR NEW.source_draft_version<>OLD.source_draft_version
  OR NEW.source_design_sha256<>OLD.source_design_sha256
  OR NEW.planning_version<>OLD.planning_version
  OR NEW.planning_binding_source<>OLD.planning_binding_source
  OR NEW.planning_binding_json<>OLD.planning_binding_json
  OR NEW.planning_binding_sha256<>OLD.planning_binding_sha256
  OR NEW.route_plan_json<>OLD.route_plan_json
  OR NEW.route_plan_sha256<>OLD.route_plan_sha256
  OR NEW.candidate_run_id<>OLD.candidate_run_id
  OR NEW.contract_version<>OLD.contract_version
  OR NEW.workflow_step<>OLD.workflow_step
  OR NEW.prepared_owner_version<>OLD.prepared_owner_version
  OR NEW.exact_title<>OLD.exact_title
  OR NEW.canonical_directory<>OLD.canonical_directory
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.managed<>OLD.managed
  OR NEW.internal_mcp_server<>OLD.internal_mcp_server
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.model_provider_id IS NOT OLD.model_provider_id
  OR NEW.model_id IS NOT OLD.model_id
  OR NEW.thinking IS NOT OLD.thinking
  OR NEW.profile<>OLD.profile
  OR NEW.permission_policy_json<>OLD.permission_policy_json
  OR NEW.permission_policy_digest<>OLD.permission_policy_digest
  OR NEW.create_request_sha256<>OLD.create_request_sha256
  OR NEW.creation_credential<>OLD.creation_credential
  OR NEW.attestation_type<>OLD.attestation_type
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch identity and plan are immutable');
END;

CREATE TRIGGER acceptance_internal_launch_create_fence_monotonic
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN NEW.create_fence<OLD.create_fence
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch create fence cannot decrease');
END;

CREATE TRIGGER acceptance_internal_launch_create_checkpoint_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.create_dispatch_attempted=1 AND (
  NEW.create_dispatch_attempted<>1
  OR NEW.create_dispatch_started_at IS NOT OLD.create_dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch create checkpoint is irreversible');
END;

CREATE TRIGGER acceptance_internal_launch_remote_attestation_once
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.external_session_id IS NOT NULL AND (
  NEW.external_session_id IS NOT OLD.external_session_id
  OR NEW.external_attested_at IS NOT OLD.external_attested_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch remote attestation is irreversible');
END;

CREATE TRIGGER acceptance_internal_launch_remote_attestation_update
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.external_session_id IS NULL AND NEW.external_session_id IS NOT NULL
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM open_code_session_runtime_binding binding
    WHERE binding.external_session_id=NEW.external_session_id
      AND binding.runtime_generation_id=NEW.runtime_generation_id
      AND binding.ownership_mode='MANAGED'
      AND binding.endpoint_fingerprint=NEW.endpoint_fingerprint
      AND binding.internal_mcp_server=NEW.internal_mcp_server
  ) THEN RAISE(ABORT,'acceptance internal launch remote attestation mismatch') END;
END;

CREATE TRIGGER acceptance_internal_launch_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.termination_proof IS NOT NULL AND (
  NEW.termination_proof IS NOT OLD.termination_proof
  OR NEW.proof_at IS NOT OLD.proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch termination proof is irreversible');
END;

CREATE TRIGGER acceptance_internal_launch_settlement_once
BEFORE UPDATE ON acceptance_candidate_internal_launch
WHEN OLD.settled_owner_version IS NOT NULL AND (
  NEW.settled_owner_version IS NOT OLD.settled_owner_version
  OR NEW.settled_at IS NOT OLD.settled_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch settlement is irreversible');
END;

-- Exact-title matches observed after an uncertain create are registered before
-- any abort is dispatched. Recovery never adopts a remaining singleton.
CREATE TABLE acceptance_candidate_internal_launch_cleanup_remote (
    launch_id TEXT NOT NULL REFERENCES acceptance_candidate_internal_launch(id) ON DELETE CASCADE,
    external_session_id TEXT NOT NULL UNIQUE,
    runtime_generation_id TEXT NOT NULL,
    endpoint_fingerprint TEXT NOT NULL CHECK (length(endpoint_fingerprint)=64),
    directory_sha256 TEXT NOT NULL CHECK (length(directory_sha256)=64),
    title_sha256 TEXT NOT NULL CHECK (length(title_sha256)=64),
    state TEXT NOT NULL CHECK (state IN ('DISCOVERED','STOPPING','STOPPED','DISCONNECTED')),
    termination_proof TEXT CHECK (termination_proof IS NULL OR termination_proof IN (
      'REMOTE_COMPLETED','ABORT_ACKNOWLEDGED','ALREADY_ABSENT')),
    proof_at TEXT,
    stop_claim_owner TEXT,
    stop_claim_token TEXT,
    stop_claim_expires_at TEXT,
    stop_fence INTEGER NOT NULL DEFAULT 0 CHECK (stop_fence >= 0),
    stop_dispatch_attempted INTEGER NOT NULL DEFAULT 0 CHECK (stop_dispatch_attempted IN (0,1)),
    stop_dispatch_started_at TEXT,
    last_error_code TEXT,
    last_error_detail TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY(launch_id,external_session_id),
    FOREIGN KEY(external_session_id,runtime_generation_id)
      REFERENCES open_code_session_runtime_binding(external_session_id,runtime_generation_id) ON DELETE RESTRICT,
    CHECK ((termination_proof IS NULL)=(proof_at IS NULL)),
    CHECK ((state='STOPPED')=(termination_proof IS NOT NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_token IS NULL)),
    CHECK ((stop_claim_owner IS NULL)=(stop_claim_expires_at IS NULL)),
    CHECK ((state='STOPPING')=(stop_claim_owner IS NOT NULL)),
    CHECK ((stop_dispatch_attempted=0 AND stop_dispatch_started_at IS NULL)
      OR (stop_dispatch_attempted=1 AND stop_dispatch_started_at IS NOT NULL))
);

CREATE INDEX idx_acceptance_internal_launch_cleanup_active
    ON acceptance_candidate_internal_launch_cleanup_remote(launch_id,state,external_session_id);

CREATE TRIGGER acceptance_internal_launch_cleanup_parent_insert
BEFORE INSERT ON acceptance_candidate_internal_launch_cleanup_remote
BEGIN
  SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch parent
    JOIN open_code_session_runtime_binding binding
      ON binding.external_session_id=NEW.external_session_id
     AND binding.runtime_generation_id=NEW.runtime_generation_id
     AND binding.ownership_mode='MANAGED'
     AND binding.endpoint_fingerprint=NEW.endpoint_fingerprint
     AND binding.internal_mcp_server=parent.internal_mcp_server
    WHERE parent.id=NEW.launch_id
      AND parent.state='STOPPING'
      AND parent.runtime_generation_id=NEW.runtime_generation_id
      AND parent.endpoint_fingerprint=NEW.endpoint_fingerprint
  ) THEN RAISE(ABORT,'acceptance internal launch cleanup parent or remote mismatch') END;
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_before_terminal
BEFORE UPDATE OF state ON acceptance_candidate_internal_launch
WHEN NEW.state IN ('SETTLED','FAILED_STOPPED','CANCELLED','STALE') AND (
  EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.id AND cleanup.state<>'STOPPED')
  OR (NEW.create_dispatch_attempted=1 AND NEW.external_session_id IS NULL AND NOT EXISTS (
    SELECT 1 FROM acceptance_candidate_internal_launch_cleanup_remote cleanup
    WHERE cleanup.launch_id=NEW.id)))
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup remotes must be stopped before terminal');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_identity_immutable
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.launch_id<>OLD.launch_id
  OR NEW.external_session_id<>OLD.external_session_id
  OR NEW.runtime_generation_id<>OLD.runtime_generation_id
  OR NEW.endpoint_fingerprint<>OLD.endpoint_fingerprint
  OR NEW.directory_sha256<>OLD.directory_sha256
  OR NEW.title_sha256<>OLD.title_sha256
  OR NEW.created_at<>OLD.created_at
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup identity is immutable');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_fence_monotonic
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN NEW.stop_fence<OLD.stop_fence
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup fence cannot decrease');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_checkpoint_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.stop_dispatch_attempted=1 AND (
  NEW.stop_dispatch_attempted<>1
  OR NEW.stop_dispatch_started_at IS NOT OLD.stop_dispatch_started_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup stop checkpoint is irreversible');
END;

CREATE TRIGGER acceptance_internal_launch_cleanup_proof_irreversible
BEFORE UPDATE ON acceptance_candidate_internal_launch_cleanup_remote
WHEN OLD.termination_proof IS NOT NULL AND (
  NEW.termination_proof IS NOT OLD.termination_proof
  OR NEW.proof_at IS NOT OLD.proof_at)
BEGIN
  SELECT RAISE(ABORT,'acceptance internal launch cleanup termination proof is irreversible');
END;
