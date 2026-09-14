package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.verification.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Recoverable two-phase generation. Receipt is durable before touching the task's output. */
@Service
public class AssistWordService {
    private static final String MEDIA="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final AssistMapper mapper;private final LoopperMapper domain;private final AssistDocumentService documents;
    private final ArtifactMaterializationService renderer;private final BinaryArtifactStore binaries;private final Path data;
    private final ObjectMapper json;private final TransactionTemplate transactions;
    private final AssistScopeService scopes;
    public AssistWordService(AssistMapper mapper,LoopperMapper domain,AssistDocumentService documents,ArtifactMaterializationService renderer,
                             BinaryArtifactStore binaries,LoopperProperties properties,ObjectMapper json,PlatformTransactionManager manager,AssistScopeService scopes) {
        this.mapper=mapper;this.domain=domain;this.documents=documents;this.renderer=renderer;this.binaries=binaries;this.data=properties.getDataDir();this.json=json;this.transactions=new TransactionTemplate(manager);
        this.scopes=scopes;
    }
    public synchronized Map<String,Object> generate(AssistScopeService.Scope scope,String source,String target,String expected,String key) {
        target=defaultTarget(scope,target);
        authorize(scope,target);if(key==null||!key.matches("[a-zA-Z0-9_-]{1,128}"))throw new AssistFailure("WORD_IDEMPOTENCY_REQUIRED","请提供 1–128 位字母数字幂等键；修改内容请使用新键");
        var input=documents.load(scope,source,expected);if(!input.name().toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown)$"))throw new AssistFailure("WORD_MARKDOWN_REQUIRED","Word 输入必须是已读取的 Markdown 文档引用");
        String text=String.join("",input.document().sections().stream().map(AssistDocumentParser.Section::markdown).toList());
        String requestHash=AssistFiles.sha((source+"\n"+input.sha256()+"\n"+target).getBytes(StandardCharsets.UTF_8));
        var receipt=mapper.word(scope.ownerKey(),key);List<String> limitations=List.of();
        if(receipt!=null&&!receipt.requestHash().equals(requestHash))throw new AssistFailure("WORD_IDEMPOTENCY_CONFLICT","幂等键已绑定其他内容，请确认最新来源并使用新键");
        if(receipt==null) {
            var output=renderer.renderMarkdownWord(text,scope.directory());limitations=output.limitations();
            var artifact=binaries.write("GENERATED_WORD",MEDIA,output.bytes(),Map.of("sourceSha256",input.sha256(),"target",target));
            receipt=new AssistMapper.WordReceipt(scope.ownerKey(),key,requestHash,target,input.sha256(),artifact.relativePath(),artifact.sha256(),"PREPARED",Instant.now().toString());mapper.insertWord(receipt);
        }
        // Stored artifact references may use Windows separators; caller-supplied paths keep strict validation.
        Path output=AssistFiles.resolve(scope.directory(),target);byte[] content=AssistFiles.read(AssistFiles.resolve(data,receipt.contentRef().replace('\\','/')),20*1024*1024);
        if(!AssistFiles.sha(content).equals(receipt.outputHash()))throw new AssistFailure("WORD_ARTIFACT_CHANGED","已登记产物内容校验失败，请检查受管存储","STOP_AND_INSPECT");
        String current=Files.exists(output)?AssistFiles.sha(AssistFiles.read(output,20*1024*1024)):null;
        if(!Objects.equals(current,receipt.outputHash())) {
            var previous=mapper.previousWord(scope.ownerKey(),target);
            if("WRITTEN".equals(receipt.state()) || current!=null&&(previous==null||!previous.outputHash().equals(current)))throw new AssistFailure("WORD_OUTPUT_MODIFIED","输出文件已存在或被用户修改，不能覆盖；请保留修改并选择新的授权目标","STOP_AND_INSPECT");
            write(scope,target,output,content,current);
        }
        complete(scope,receipt,content.length);
        return Map.of("path",target,"sha256",receipt.outputHash(),"sizeBytes",content.length,"structureCheck","REOPENED",
                "limitations",limitations,"detail","已登记 Word 产物；仍需正式文档验证和内容验收");
    }
    private void complete(AssistScopeService.Scope scope,AssistMapper.WordReceipt receipt,int size) {
        transactions.executeWithoutResult(status->{
            var current=mapper.word(scope.ownerKey(),receipt.idempotencyKey());if("WRITTEN".equals(current.state()))return;
            domain.insertBinaryArtifact(new BinaryArtifactRow(UUID.randomUUID().toString(),scope.taskId(),scope.attemptId(),null,null,"GENERATED_WORD",MEDIA,
                    receipt.contentRef(),receipt.outputHash(),size,json.writeValueAsString(Map.of("path",receipt.targetPath(),"sourceSha256",receipt.sourceHash())),Instant.now().toString()));
            domain.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(),scope.taskId(),scope.attemptId(),null,"GENERATED_WORD","Word 生成回执","application/json",
                    json.writeValueAsString(Map.of("path",receipt.targetPath(),"sha256",receipt.outputHash(),"sizeBytes",size)),"{}",Instant.now().toString()));
            mapper.completeWord(scope.ownerKey(),receipt.idempotencyKey());
        });
    }
    private void write(AssistScopeService.Scope scope,String target,Path output,byte[] content,String previous) {
        try {authorize(scope,target);Files.createDirectories(output.getParent());AssistFiles.resolve(scope.directory(),target);
            Path temp=Files.createTempFile(output.getParent(),"loopper-word-",".tmp");
            try {Files.write(temp,content);String actual=Files.exists(output)?AssistFiles.sha(AssistFiles.read(output,20*1024*1024)):null;
                if(!Objects.equals(actual,previous))throw new AssistFailure("WORD_OUTPUT_MODIFIED","落盘前文件发生变化，请保留用户修改","STOP_AND_INSPECT");
                authorize(scope,target);
                if(previous==null){Files.createLink(output,temp);}else Files.move(temp,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally{Files.deleteIfExists(temp);}
        }catch(java.io.IOException e){throw new AssistFailure("WORD_WRITE_INTERRUPTED","产物已保存，但输出写入未完成；检查目录后使用同一幂等键恢复","STOP_AND_INSPECT");}
    }
    private void authorize(AssistScopeService.Scope scope,String target) {
        var active=scopes.resolve(scope.externalSessionId());if(!Objects.equals(active.attemptId(),scope.attemptId()))throw denied();
        if(!"IMPLEMENTATION".equals(scope.profile())||scope.stageId()==null||target==null||!target.toLowerCase(Locale.ROOT).endsWith(".docx"))throw denied();
        var owner=mapper.executionOwner(scope.externalSessionId());if(owner==null||!Objects.equals(owner.attemptId(),scope.attemptId()))throw denied();
        var stage=domain.findStage(scope.stageId()).orElseThrow(AssistWordService::denied);var rules=VerifierPathPolicy.boundedRuleRelations();
        List<String> deliverables=strings(stage.deliverablesJson()),allowed=strings(stage.allowedPathsJson()),forbidden=strings(stage.forbiddenPathsJson());
        if(deliverables.stream().noneMatch(d->d.equals(target)||d.contains("`"+target+"`"))
                ||allowed.stream().noneMatch(a->rules.allowedRuleCoversExactPath(target,a))||forbidden.stream().anyMatch(f->rules.ruleMatchesExactPath(target,f)))throw denied();
        AssistFiles.resolve(scope.directory(),target);
    }
    private List<String> strings(String value){return json.readValue(value,new tools.jackson.core.type.TypeReference<>(){});}
    private String defaultTarget(AssistScopeService.Scope scope,String target) {
        if(target!=null&&!target.isBlank())return target;
        if(scope.stageId()==null)throw denied();var stage=domain.findStage(scope.stageId()).orElseThrow(AssistWordService::denied);
        var paths=strings(stage.deliverablesJson()).stream().filter(d->d.toLowerCase(Locale.ROOT).endsWith(".docx")&&!d.contains(" ")&&!d.contains("`")).toList();
        if(paths.size()!=1)throw new AssistFailure("WORD_TARGET_REQUIRED","当前阶段存在多个目标或没有精确 DOCX 路径，请指定已授权目标");return paths.getFirst();
    }
    private static AssistFailure denied(){return new AssistFailure("WORD_TARGET_NOT_AUTHORIZED","当前运行阶段必须在 deliverables 中明确登记此 DOCX 路径，并允许写入该路径；请通过任务设计调整合同","REAUTHORIZE");}
}
