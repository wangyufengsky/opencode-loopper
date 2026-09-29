package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import io.opencode.loopper.service.ConflictException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Freezes native report bytes once, independently of final node settlement, for crash-safe replay. */
@Service
@Transactional(readOnly=true)
public class WorkflowNativeTestEvidence {
    private final WorkflowNativeTestMapper mapper;
    private final WorkflowCommandRunMapper commands;
    private final WorkflowEncoding encoding;
    public WorkflowNativeTestEvidence(WorkflowNativeTestMapper mapper,WorkflowCommandRunMapper commands,WorkflowEncoding encoding){this.mapper=mapper;this.commands=commands;this.encoding=encoding;}
    public Optional<WorkflowNativeTestMapper.Evidence> find(String id) {
        var row=mapper.find(id);row.ifPresent(value->{if(!WorkflowEncoding.hash(value.reportJson()).equals(value.reportSha256())||!WorkflowEncoding.hash(value.resultJson()).equals(value.resultSha256()))throw invalid();});return row;
    }
    @Transactional
    public WorkflowNativeTestMapper.Evidence save(String id,Result result,WorkflowNativeTestReports.Report report) {
        var run=commands.find(id).orElseThrow(WorkflowNativeTestEvidence::invalid);
        if(!Set.of("READY","RUNNING","STOPPING").contains(run.state())||!result.stopConfirmed()||!Objects.equals(run.requestSha256(),result.requestSha256())||run.registrationJson()==null
                ||!encoding.decode(run.registrationJson(),Registration.class).worker().equals(result.worker()))throw invalid();
        String resultJson=encoding.encode(result);var old=find(id);
        if(old.isPresent()){if(!old.get().resultJson().equals(resultJson))throw invalid();return old.get();}
        String json=encoding.encode(report);var row=new WorkflowNativeTestMapper.Evidence(id,result.requestSha256(),resultJson,WorkflowEncoding.hash(resultJson),json,WorkflowEncoding.hash(json),Instant.now().toString());
        if(mapper.insert(row)!=1)throw invalid();return row;
    }
    public WorkflowNativeTestReports.Report report(String id,Result result) {
        var row=find(id).orElseThrow(WorkflowNativeTestEvidence::invalid);
        if(!row.resultJson().equals(encoding.encode(result))||!row.requestSha256().equals(result.requestSha256()))throw invalid();
        return encoding.decode(row.reportJson(),WorkflowNativeTestReports.Report.class);
    }
    public Request request(String id,Result result) {
        var row=commands.find(id).orElseThrow(WorkflowNativeTestEvidence::invalid);
        var request=encoding.decode(row.requestJson(),Request.class);
        try {
            if(!io.opencode.loopper.runtime.DurableCommandProtocol.hash(io.opencode.loopper.runtime.DurableCommandProtocol.request(request)).equals(result.requestSha256()))throw invalid();
            return request;
        }catch(java.io.IOException failure){throw invalid();}
    }
    private static ConflictException invalid(){return new ConflictException("WORKFLOW_TEST_EVIDENCE_INVALID","原生测试证据与本次执行不一致，请保留原执行记录并检查数据。");}
}
