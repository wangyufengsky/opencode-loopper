package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowDirectoryMapper;
import io.opencode.loopper.persistence.WorkflowDirectoryMapper.Preparation;
import io.opencode.loopper.persistence.WorkflowDirectoryMapper.Apply;
import io.opencode.loopper.persistence.WorkflowWorkspaceMapper.Workspace;
import io.opencode.loopper.runtime.DirectWorkspaceLeaseCoordinator.WorkspaceIdentity;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.workflow.WorkflowDirectorySnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable preparation facts in short transactions; never performs filesystem/Git I/O. */
@Service
@Transactional(readOnly=true)
public class WorkflowDirectoryStore {
    private final WorkflowDirectoryMapper mapper;
    private final WorkflowWorkspaceStore workspaces;
    private final WorkflowWriterLeases leases;
    private final WorkflowEncoding encoding;
    private final io.opencode.loopper.persistence.WorkflowModelMapper models;
    public WorkflowDirectoryStore(WorkflowDirectoryMapper mapper, WorkflowWorkspaceStore workspaces,
                                  WorkflowWriterLeases leases, WorkflowEncoding encoding,io.opencode.loopper.persistence.WorkflowModelMapper models) {
        this.mapper=mapper; this.workspaces=workspaces; this.leases=leases; this.encoding=encoding;this.models=models;
    }
    @Transactional
    public Preparation reserve(WorkflowWorkspaceStore.Context expected, WorkspaceIdentity identity, String repository) {
        requireCurrent(expected, identity);
        var row=mapper.find(expected.attempt().id()).orElse(null);
        if (row!=null) {
            if (!row.canonicalRoot().equals(identity.canonicalRoot()) || !row.rootFingerprint().equals(identity.rootFingerprint())
                    || !row.objectRepository().equals(repository) || !row.inputsSha256().equals(expected.attempt().inputsSha256())) throw invalid();
            return row;
        }
        row=new Preparation(expected.attempt().id(), expected.owner().id(), expected.project().id(), identity.canonicalRoot(),
                identity.rootFingerprint(), repository, expected.attempt().inputsSha256(), null,null,null,null,Instant.now().toString());
        if (mapper.insert(row)!=1) throw invalid(); return row;
    }
    @Transactional
    public Preparation manifest(WorkflowWorkspaceStore.Context expected, Preparation prepared, WorkflowDirectorySnapshot snapshot) {
        requireCurrent(expected, identity(prepared)); requireOwner(expected,prepared);
        if (snapshot.version()!=1 || !snapshot.canonicalRoot().equals(prepared.canonicalRoot())
                || !snapshot.rootFingerprint().equals(prepared.rootFingerprint())) throw invalid();
        GitDirectoryTrees.validate(snapshot.files());
        String body=encoding.encode(snapshot), hash=WorkflowEncoding.hash(body);
        if (body.getBytes(StandardCharsets.UTF_8).length>16*1024*1024) throw invalid();
        var row=mapper.find(prepared.attemptId()).orElseThrow(WorkflowDirectoryStore::invalid);
        if (row.manifestJson()!=null) {
            if (!row.manifestJson().equals(body) || !row.manifestSha256().equals(hash)) throw invalid();
        } else if (mapper.manifest(row.attemptId(),body,hash)!=1) throw invalid();
        return mapper.find(row.attemptId()).orElseThrow(WorkflowDirectoryStore::invalid);
    }
    @Transactional
    public Preparation ready(WorkflowWorkspaceStore.Context expected, Preparation prepared, String tree, String commit) {
        requireCurrent(expected, identity(prepared)); requireOwner(expected,prepared); snapshot(prepared);
        if (tree==null || commit==null || !tree.matches("[0-9a-f]{40}") || !commit.matches("[0-9a-f]{40}")) throw invalid();
        var row=mapper.find(prepared.attemptId()).orElseThrow(WorkflowDirectoryStore::invalid);
        if (row.baseTree()!=null) {
            if (!row.baseTree().equals(tree) || !row.sourceCommit().equals(commit)) throw invalid();
        } else if (mapper.ready(row.attemptId(),prepared.manifestSha256(),tree,commit)!=1) throw invalid();
        return mapper.find(row.attemptId()).orElseThrow(WorkflowDirectoryStore::invalid);
    }
    public WorkflowDirectorySnapshot snapshot(Preparation row) {
        if (row.manifestJson()==null || !WorkflowEncoding.hash(row.manifestJson()).equals(row.manifestSha256())) throw invalid();
        var value=encoding.decode(row.manifestJson(),WorkflowDirectorySnapshot.class);
        if (value.version()!=1 || !value.canonicalRoot().equals(row.canonicalRoot()) || !value.rootFingerprint().equals(row.rootFingerprint())) throw invalid();
        GitDirectoryTrees.validate(value.files()); return value;
    }
    public Preparation preparation(String id) { return mapper.find(id).orElseThrow(WorkflowDirectoryStore::invalid); }
    public boolean hasPreparation(String id) { return mapper.find(id).isPresent(); }
    public boolean executionPlanned(String id) {
        return workspaces.context(id).attempt().externalSessionId()!=null||models.find(id).map(row->row.creationPlanJson()!=null).orElse(false);
    }
    public Optional<WorkflowDirectorySnapshot> result(String id) {
        var prepared=preparation(id);return mapper.result(id).map(row->decode(prepared,row.manifestJson(),row.manifestSha256()));
    }
    @Transactional
    public WorkflowDirectorySnapshot freezeResult(Workspace expected,WorkflowDirectorySnapshot snapshot) {
        requireWorkspace(expected,true,"CAPTURING");var prepared=preparation(expected.attemptId());
        String body=body(prepared,snapshot),hash=WorkflowEncoding.hash(body);
        var existing=mapper.result(expected.attemptId()).orElse(null);
        if(existing!=null) {
            if(!existing.manifestJson().equals(body)||!existing.manifestSha256().equals(hash))throw invalid();
        } else if(mapper.insertResult(new WorkflowDirectoryMapper.Result(expected.attemptId(),body,hash,Instant.now().toString()))!=1)throw invalid();
        return snapshot;
    }
    @Transactional
    public Apply apply(Workspace expected,String phase,WorkflowDirectorySnapshot before,WorkflowDirectorySnapshot after) {
        if(!Set.of("SEED","RESTORE").contains(phase))throw invalid();
        requireWorkspace(expected,phase.equals("RESTORE"),phase.equals("SEED")?"PREPARING":"RESTORING");
        var prepared=preparation(expected.attemptId());String first=body(prepared,before),last=body(prepared,after);
        var row=mapper.apply(expected.attemptId(),phase).orElse(null);
        if(row!=null) {
            if(!row.beforeJson().equals(first)||!row.afterJson().equals(last))throw invalid();return row;
        }
        row=new Apply(UUID.randomUUID().toString(),expected.attemptId(),phase,first,WorkflowEncoding.hash(first),last,WorkflowEncoding.hash(last),Instant.now().toString());
        if(mapper.insertApply(row)!=1)throw invalid();return row;
    }
    public Optional<Apply> apply(String id,String phase) { return mapper.apply(id,phase); }
    public List<Apply> applies(String id) { return mapper.applies(id); }
    public WorkflowDirectorySnapshot before(Apply row) { return decode(preparation(row.attemptId()),row.beforeJson(),row.beforeSha256()); }
    public WorkflowDirectorySnapshot after(Apply row) { return decode(preparation(row.attemptId()),row.afterJson(),row.afterSha256()); }
    private WorkflowDirectorySnapshot decode(Preparation row,String body,String hash) {
        if(!WorkflowEncoding.hash(body).equals(hash))throw invalid();var value=encoding.decode(body,WorkflowDirectorySnapshot.class);
        body(row,value);return value;
    }
    private String body(Preparation row,WorkflowDirectorySnapshot value) {
        if(value.version()!=1 || !value.canonicalRoot().equals(row.canonicalRoot()) || !value.rootFingerprint().equals(row.rootFingerprint()))throw invalid();
        GitDirectoryTrees.validate(value.files());String body=encoding.encode(value);
        if(body.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw invalid();return body;
    }
    private void requireWorkspace(Workspace expected,boolean stopped,String state) {
        var actual=workspaces.require(expected.attemptId());
        if(!actual.equals(expected)||!actual.state().equals(state)||actual.objectRepository()==null)throw invalid();
        workspaces.holder(actual,new WorkspaceIdentity(actual.canonicalRoot(),actual.rootFingerprint()),stopped);
    }
    private void requireCurrent(WorkflowWorkspaceStore.Context expected, WorkspaceIdentity identity) {
        var current=workspaces.context(expected.attempt().id());
        if (current.attempt().version()!=expected.attempt().version() || !current.attempt().state().equals("PREPARING")
                || current.attempt().externalSessionId()!=null || current.project().version()!=expected.project().version()
                || !current.project().rootPath().equals(expected.project().rootPath())) throw invalid();
        leases.requireWritable(identity,current.attempt().id());
    }
    private void requireOwner(WorkflowWorkspaceStore.Context expected, Preparation row) {
        if (!row.attemptId().equals(expected.attempt().id()) || !row.requirementId().equals(expected.owner().id())
                || !row.projectId().equals(expected.project().id()) || !row.inputsSha256().equals(expected.attempt().inputsSha256())) throw invalid();
    }
    private static WorkspaceIdentity identity(Preparation row) { return new WorkspaceIdentity(row.canonicalRoot(),row.rootFingerprint()); }
    private static ConflictException invalid() { return new ConflictException("WORKFLOW_DIRECTORY_PREPARATION_CHANGED", "目录准备记录、输入或写入许可已变化，请保留原记录恢复"); }
}
