package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.BatchAssistMapper;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.SafeProcessRunner;
import io.opencode.loopper.service.ConflictException;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class BatchAssistConfigService {
    public record Source(String kind, String root, String pattern) { }
    public record Config(String instance, String repository, Long projectId, String name, String webUrl,
                         String checkedAt, List<Source> sources) {
        public Config { sources = sources == null ? List.of() : List.copyOf(sources); }
        public static Config empty() { return new Config(null,null,null,null,null,null,List.of()); }
    }
    public record Request(long version, String repository, List<Source> sources) { }
    public record View(long version, Config config, boolean credentialConfigured) { }
    private final BatchAssistMapper mapper;
    private final LoopperMapper domain;
    private final GitLabReadTransport gitlab;
    private final SafeProcessRunner runner;
    private final ObjectMapper json;
    public BatchAssistConfigService(BatchAssistMapper mapper, LoopperMapper domain, GitLabReadTransport gitlab,
                                   SafeProcessRunner runner, ObjectMapper json) {
        this.mapper=mapper; this.domain=domain; this.gitlab=gitlab; this.runner=runner; this.json=json;
    }
    public View get(String project) {
        requireProject(project);
        var row=mapper.config(project);
        return new View(row == null ? -1 : row.version(), row == null ? Config.empty() : parse(row.configJson()), gitlab.credentialConfigured());
    }
    public View save(String project, Request request) {
        requireProject(project);
        String repository=normalizeRepository(request.repository());
        List<Source> sources=validateSources(request.sources());
        Config old=get(project).config();
        String instance=repository.isBlank()?null:gitlab.instance();
        boolean same=Objects.equals(old.repository(),repository) && Objects.equals(old.instance(),instance);
        Config next=new Config(instance,repository,same?old.projectId():null,same?old.name():null,
                same?old.webUrl():null,same?old.checkedAt():null,sources);
        update(project,request.version(),next);
        return get(project);
    }
    public View check(String project, long version) {
        var view=get(project); if(view.version()!=version) throw conflict();
        Config saved=view.config();
        if(saved.repository()==null || saved.repository().isBlank()) throw new AssistFailure("GITLAB_BINDING_MISSING","请先保存仓库路径");
        var response=gitlab.get(saved.instance(),"/projects/"+GitLabReadTransport.encode(saved.repository()));
        GitLabReadTransport.requireSuccess(response);
        if(response.truncated()) throw new AssistFailure("GITLAB_RESPONSE_LIMIT","项目数据超过读取上限");
        var root=json.readTree(response.body());
        long id=root.path("id").asLong();
        if(id<=0 || !saved.repository().equalsIgnoreCase(root.path("path_with_namespace").asText()))
            throw new AssistFailure("GITLAB_PROJECT_MISMATCH","GitLab 返回的仓库不匹配，请核对绑定路径");
        update(project,version,new Config(saved.instance(),saved.repository(),id,root.path("name").asText(),
                root.path("web_url").asText(),Instant.now().toString(),saved.sources()));
        return get(project);
    }
    public Map<String,String> discover(String project) {
        Path root=requireProject(project);
        var result=runner.run(root,List.of("git","remote","get-url","origin"),Duration.ofSeconds(3));
        if(result.exitCode()!=0 || result.timedOut() || result.outputTruncated())
            throw new AssistFailure("GITLAB_REMOTE_UNAVAILABLE","无法读取 origin，请手动填写仓库路径");
        String remote=result.output().strip(),host,path;
        try {
            if(remote.contains("://")) { URI uri=URI.create(remote); host=uri.getHost(); path=uri.getPath().replaceFirst("^/",""); }
            else { int colon=remote.indexOf(':'); host=remote.substring(0,colon).replaceFirst("^.*@",""); path=remote.substring(colon+1); }
            if(!Objects.equals(host,URI.create(gitlab.instance()).getHost())) throw new IllegalArgumentException();
            return Map.of("repository",normalizeRepository(path.replaceFirst("\\.git$","")));
        } catch(RuntimeException failure) { throw new AssistFailure("GITLAB_REMOTE_MISMATCH","origin 不是已配置的 GitLab，请手动填写并核对仓库路径"); }
    }
    public Config frozen(String owner, String project) {
        String value=mapper.binding(owner);
        if(value==null) {
            // Missing old Task bindings never acquire live project authorization.
            Config config=owner.startsWith("TASK:")?Config.empty():get(project).config();
            mapper.bind(owner,json.writeValueAsString(config),Instant.now().toString()); value=mapper.binding(owner);
        }
        return parse(value);
    }
    private void update(String project,long version,Config config) {
        var row=new BatchAssistMapper.Config(project,json.writeValueAsString(config),version,Instant.now().toString());
        if((version<0?mapper.insertConfig(row):mapper.updateConfig(row))!=1) throw conflict();
    }
    private Config parse(String value) { return value==null||value.equals("{}")?Config.empty():json.readValue(value,Config.class); }
    private Path requireProject(String id) {
        return Path.of(domain.findProject(id).orElseThrow(()->new AssistFailure("PROJECT_MISSING","项目不存在，请刷新列表")).rootPath());
    }
    private static String normalizeRepository(String path) {
        String value=path==null?"":path.strip();
        if(!value.isEmpty() && (!value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)+") || value.length()>512 || value.contains("/../")))
            throw new AssistFailure("GITLAB_PATH_INVALID","请填写 group/subgroup/repository 格式的仓库路径");
        return value;
    }
    private static List<Source> validateSources(List<Source> sources) {
        if(sources==null) return List.of();
        if(sources.size()>16) throw new AssistFailure("EVIDENCE_SOURCE_LIMIT","最多登记 16 条证据来源规则");
        for(Source source:sources) {
            if(source==null || source.kind()==null || !Set.of("JUNIT","LOG").contains(source.kind()) || source.pattern()==null
                    || source.pattern().isBlank() || source.pattern().length()>256 || source.pattern().contains("..") || source.pattern().contains("\\")
                    || source.pattern().startsWith("/") || source.pattern().contains(":"))
                throw new AssistFailure("EVIDENCE_SOURCE_INVALID","请选择报告或日志并填写目录内文件规则");
            try { FileSystems.getDefault().getPathMatcher("glob:"+source.pattern()); }
            catch(RuntimeException invalid) { throw new AssistFailure("EVIDENCE_PATTERN_INVALID","文件匹配规则无效，请修正"); }
            if(source.root()!=null && !source.root().isBlank()) {
                if(!source.kind().equals("LOG")) throw new AssistFailure("EVIDENCE_ROOT_INVALID","JUnit 报告只允许任务工作目录");
                EvidenceSourceScanner.externalRoot(source.root());
            }
        }
        return List.copyOf(sources);
    }
    private static ConflictException conflict() { return new ConflictException("ASSIST_CONFIG_CONFLICT","项目辅助配置已变化，请刷新后重试"); }
}
