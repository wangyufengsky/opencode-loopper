package io.opencode.loopper.service.assist;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.BatchAssistMapper;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.verification.VerifierOutcome;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Executed outside lifecycle transactions. Evidence identity is fixed before the command starts. */
@Service
public class ExecutionEvidenceCapture {
    private final BatchAssistConfigService configs;
    private final EvidenceSnapshotStore store;
    private final BatchAssistMapper mapper;
    private final LoopperMapper domain;
    private final ObjectMapper json;
    public ExecutionEvidenceCapture(BatchAssistConfigService configs,EvidenceSnapshotStore store,BatchAssistMapper mapper,LoopperMapper domain,ObjectMapper json) {
        this.configs=configs;this.store=store;this.mapper=mapper;this.domain=domain;this.json=json;
    }
    public VerifierOutcome capture(String task,String stage,String attempt,String execution,Path workspace,LoopSpec.VerifierSpec spec,
                                   Function<Consumer<ProcessResult>,VerifierOutcome> action) {
        var owner=new EvidenceSnapshotStore.Owner("TASK:"+task,task,stage,attempt,execution);
        var current=domain.findTask(task).orElseThrow(()->new AssistFailure("TASK_MISSING","任务不存在"));
        var config=configs.frozen(owner.key(),current.projectId());
        List<BatchAssistConfigService.Source> sources=new ArrayList<>(config.sources());
        if("JUNIT_XML".equalsIgnoreCase(spec.type()) && spec.path()!=null)
            sources.add(new BatchAssistConfigService.Source("JUNIT","",spec.path()));
        var before=EvidenceSourceScanner.scan(workspace,sources);String started=Instant.now().toString();
        try {
            return action.apply(result -> safeSave(owner,"PROCESS",String.join(" ",spec.command()),result.output(),
                    result.outputTruncated()?"TRUNCATED":"COMPLETE",Map.of("startedAt",started,"exitCode",result.exitCode(),
                            "timedOut",result.timedOut(),"mergedStreams",true,"outputTruncated",result.outputTruncated())));
        } catch(RuntimeException interrupted) {
            safeSave(owner,"CAPTURE",spec.type(),"","INCOMPLETE",Map.of("startedAt",started,"detail","正式验证异常或中断；以原验证错误为准"));throw interrupted;
        } finally {
            collect(owner,workspace,sources,before,started);
        }
    }
    private void collect(EvidenceSnapshotStore.Owner owner,Path workspace,List<BatchAssistConfigService.Source> sources,
                         EvidenceSourceScanner.Scan baseline,String started) {
        var after=EvidenceSourceScanner.scan(workspace,sources);
        long remaining=32L*1024*1024;
        for(var source:after.files()) {
            long cost=Math.min(source.size(),source.kind().equals("JUNIT")?4_000_000:2L*1024*1024);
            if(cost>remaining) {safeSave(owner,"CAPTURE","证据来源检查","","LIMIT",Map.of("detail","达到本轮读取上限"));break;}
            remaining-=cost;
            var old=baseline.files().stream().filter(f->f.path().equals(source.path())).findFirst().orElse(null);
            try {
                if(source.kind().equals("JUNIT")) report(owner,source,old,baseline.complete(),started);
                else log(owner,source,old,baseline.complete(),started);
            } catch(RuntimeException failure) {
                safeSave(owner,source.kind(),source.path().toString(),"","UNAVAILABLE",Map.of("detail","来源不可读取或超过上限","startedAt",started));
            }
        }
        if(!after.issues().isEmpty() || !baseline.complete()) safeSave(owner,"CAPTURE", "证据来源检查", "", "INCOMPLETE",
                Map.of("startedAt",started,"before",baseline.issues(),"after",after.issues()));
    }
    private void report(EvidenceSnapshotStore.Owner owner,EvidenceSourceScanner.FileSource source,EvidenceSourceScanner.FileSource old,boolean baselineComplete,String started) {
        byte[] bytes=AssistFiles.read(AssistFiles.resolve(source.root(),source.root().relativize(source.path()).toString().replace('\\','/')),4_000_000);
        String rawSha=AssistFiles.sha(bytes);
        boolean stable=rawSha.equals(source.sha());
        boolean fresh=stable && (old==null?baselineComplete:!rawSha.equals(old.sha()) || !source.identity().equals(old.identity()));
        String content=store.redact(JunitEvidenceParser.decode(bytes));
        String sha=AssistFiles.sha(content.getBytes(StandardCharsets.UTF_8));
        String cached=mapper.parsed(sha,JunitEvidenceParser.VERSION);
        var parsed=cached==null?new JunitEvidenceParser().parse(content):json.readValue(cached,JunitEvidenceParser.Parsed.class);
        if(cached==null) mapper.cache(sha,JunitEvidenceParser.VERSION,json.writeValueAsString(parsed));
        var facts=new LinkedHashMap<String,Object>();facts.put("startedAt",started);facts.put("sourceSha256",rawSha);
        facts.put("freshness",fresh?"CHANGED_DURING_VERIFICATION":"UNCONFIRMED");facts.put("parserVersion",JunitEvidenceParser.VERSION);
        facts.put("tests",parsed.tests());facts.put("failures",parsed.failures());facts.put("errors",parsed.errors());facts.put("skipped",parsed.skipped());
        facts.put("detail",parsed.detail());facts.put("acceptance",false);
        var row=safeSave(owner,"JUNIT",source.path().toString(),content,!parsed.complete()||!stable?"INCOMPLETE":fresh?"COMPLETE":"UNCONFIRMED",facts);
        if(row!=null && !Set.of("LIMIT","UNAVAILABLE","PREPARED").contains(row.status())) {
            for(var item:parsed.cases()) mapper.insertFailure(new BatchAssistMapper.TestFailure(row.createdAt()+"|"+row.id()+":"+String.format(Locale.ROOT,"%04d",item.index()),
                    row.id(),item.name(),item.className(),item.state(),json.writeValueAsString(item)));
        }
    }
    private void log(EvidenceSnapshotStore.Owner owner,EvidenceSourceScanner.FileSource source,EvidenceSourceScanner.FileSource old,boolean baselineComplete,String started) {
        boolean same=old!=null && old.identity().equals(source.identity()) && source.size()>=old.size();
        long offset=same?old.size():0;
        boolean truncated=source.size()-offset>2L*1024*1024;
        long start=Math.max(offset,source.size()-2L*1024*1024);
        Path safe=AssistFiles.resolve(source.root(),source.root().relativize(source.path()).toString().replace('\\','/'));
        try(SeekableByteChannel channel=Files.newByteChannel(safe,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            channel.position(start);ByteBuffer buffer=ByteBuffer.allocate((int)(source.size()-start));
            while(buffer.hasRemaining() && channel.read(buffer)>0) { }
            boolean changed=channel.size()!=source.size();
            String state=truncated?"TRUNCATED":changed||old!=null&&!same||!baselineComplete?"INCOMPLETE":"COMPLETE";
            safeSave(owner,"LOG",source.path().toString(),new String(buffer.array(),0,buffer.position(),StandardCharsets.UTF_8),state,
                    Map.of("startedAt",started,"offset",start,"endOffset",start+buffer.position(),"sharedObservation",true,"rotatedOrTruncated",old!=null&&!same));
        } catch(java.io.IOException failure) { throw new AssistFailure("LOG_UNAVAILABLE","日志文件不可读取"); }
    }
    private BatchAssistMapper.Evidence safeSave(EvidenceSnapshotStore.Owner owner,String kind,String source,String content,String status,Map<String,Object> facts) {
        try {
            var attempt=domain.findAttempt(owner.attempt()).orElse(null);
            if(attempt==null || !Objects.equals(attempt.stageId(),owner.stage())) return null;
            // Never attach a late result to a replacement Attempt or change lifecycle state.
            return store.save(owner,kind,source,content,status,facts);
        } catch(RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Evidence capture unavailable for execution {}",owner.execution());
            return null;
        }
    }
}
