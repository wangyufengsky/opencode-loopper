package io.opencode.loopper.service.assist;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.BatchAssistMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceSnapshotStore {
    private final BatchAssistMapper mapper;
    private final LoopperProperties properties;
    private final ObjectMapper json;
    private final Path root;
    public record Owner(String key,String task,String stage,String attempt,String execution) { }
    public EvidenceSnapshotStore(BatchAssistMapper mapper,LoopperProperties properties,ObjectMapper json) {
        this.mapper=mapper;this.properties=properties;this.json=json;
        root=properties.getDataDir().toAbsolutePath().normalize().resolve("execution-evidence");
    }
    public String redact(String text) {
        String result=AssistRedaction.text(text==null?"":text);
        String token=properties.getPublication().getGitlab().getPrivateToken();
        if(token!=null && !token.isBlank()) result=result.replace(token,"[凭证已隐藏]");
        return result.replaceAll("(?i)((?:password|passwd|private[-_]token|access[-_]token|authorization|api[-_]key)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',}]+", "$1[凭证已隐藏]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [凭证已隐藏]");
    }
    private Object sanitize(Object value) {
        if(value instanceof String text) return redact(text);
        if(value instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();map.forEach((key,item)->result.put(key.toString(),sanitize(item)));return result;
        }
        if(value instanceof List<?> list) return list.stream().map(this::sanitize).toList();
        return value;
    }
    public synchronized BatchAssistMapper.Evidence save(Owner owner,String kind,String source,String content,String status,Map<String,Object> metadata) {
        byte[] bytes=redact(content).getBytes(StandardCharsets.UTF_8);
        var facts=new LinkedHashMap<>(metadata);
        if(bytes.length>4_000_000 || mapper.size(owner.key())+bytes.length>256L*1024*1024
                || owner.attempt()!=null && (mapper.attemptSize(owner.attempt())+bytes.length>32L*1024*1024
                || Set.of("LOG","JUNIT").contains(kind) && mapper.files(owner.attempt())>=64)) {
            bytes=new byte[0];status="LIMIT";facts.put("detail","证据存储达到上限，本次未完整采集");
        }
        String id=UUID.randomUUID().toString(),filename=id+".txt",hash=AssistFiles.sha(bytes),time=Instant.now().toString();
        var prepared=new BatchAssistMapper.Evidence(id,owner.key(),owner.task(),owner.stage(),owner.attempt(),owner.execution(),kind,
                redact(source),filename,hash,bytes.length,"PREPARED",json.writeValueAsString(sanitize(facts)),time);
        mapper.insertEvidence(prepared);
        try {
            Files.createDirectories(root);
            Path target=AssistFiles.resolve(root,filename),temporary=root.resolve(id+".part");
            Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException|RuntimeException failure) {
            status="UNAVAILABLE";facts.put("detail","证据文件未能持久化，请检查数据目录和磁盘空间");
        }
        mapper.finishEvidence(id,status,json.writeValueAsString(sanitize(facts)),Instant.now().toString());
        return mapper.evidence(owner.key(),id,"9999");
    }
    public String read(BatchAssistMapper.Evidence row) {
        if(row==null) throw new AssistFailure("EVIDENCE_SCOPE_DENIED","证据不存在或不属于当前授权范围");
        if(Set.of("PREPARED","UNAVAILABLE").contains(row.status())) throw new AssistFailure("EVIDENCE_UNAVAILABLE","证据未完成持久化，请查看来源状态");
        byte[] bytes=AssistFiles.read(AssistFiles.resolve(root,row.contentPath()),4_000_000);
        if(!AssistFiles.sha(bytes).equals(row.sha256())) throw new AssistFailure("EVIDENCE_HASH_MISMATCH","证据文件已变化，请检查数据备份");
        return new String(bytes,StandardCharsets.UTF_8);
    }
    public Map<String,Object> metadata(BatchAssistMapper.Evidence row) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.id()); result.put("reference","snapshot:"+row.id()); result.put("snapshotReference","snapshot:"+row.id()); result.put("kind",row.kind());
        result.put("source",row.source()); result.put("status",row.status()); result.put("sha256",row.sha256());
        result.put("byteSize",row.byteSize()); result.put("createdAt",row.createdAt()); result.put("attemptId",row.attemptId());
        result.put("executionId",row.executionId()); result.put("details",json.readTree(row.metadataJson()));
        return result;
    }
}
