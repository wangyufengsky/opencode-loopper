package io.opencode.loopper.service.assist;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GitLabAssistService {
    private final BatchAssistConfigService configs;
    private final GitLabReadTransport transport;
    private final EvidenceSnapshotStore snapshots;
    private final BatchEvidenceReadService reads;
    private final ObjectMapper json;
    public GitLabAssistService(BatchAssistConfigService configs,GitLabReadTransport transport,EvidenceSnapshotStore snapshots,
                               BatchEvidenceReadService reads,ObjectMapper json) {
        this.configs=configs;this.transport=transport;this.snapshots=snapshots;this.reads=reads;this.json=json;
    }
    public Map<String,Object> call(AssistScopeService.Scope scope,String name,Map<String,Object> args) {
        if(scope.profile().contains("JUDGE")||scope.profile().contains("REVIEWER"))throw new AssistFailure("GITLAB_SCOPE_DENIED","评审只可读取已保存证据");
        var config=configs.frozen(scope.ownerKey(),scope.projectId());
        if(config.projectId()==null)throw new AssistFailure("GITLAB_BINDING_UNVERIFIED","任务未冻结已核验的 GitLab 仓库，请在项目页检查绑定后创建新任务");
        String reference=text(args,"reference");
        if(reference!=null&&!reference.isBlank()) return reads.read(scope.ownerKey(),reads.before(scope),reference,number(args,"offset",0));
        String base="/projects/"+config.projectId();
        String path=path(name,args);String pagination=pagination(args);
        boolean list=path.endsWith("discussions")||name.contains("list_")||name.endsWith("_diff");
        var response=transport.get(config.instance(),base+path+(list?pagination:""));
        if(name.equals("gitlab_read_merge_request_diff") && Set.of(404,405).contains(response.status())) {
            var parent=transport.get(config.instance(),base+"/merge_requests/"+positive(args,"iid"));
            GitLabReadTransport.requireSuccess(parent);
            response=transport.get(config.instance(),base+"/merge_requests/"+positive(args,"iid")+"/changes");
        }
        GitLabReadTransport.requireSuccess(response);
        boolean log=name.equals("gitlab_read_job_log");
        if(!log && response.truncated()) throw new AssistFailure("GITLAB_RESPONSE_LIMIT","GitLab 响应超过 1 MiB，请缩小分页或查询范围");
        if(!log) {
            try {json.readTree(response.body());}catch(RuntimeException invalid){throw new AssistFailure("GITLAB_RESPONSE_INVALID","GitLab 返回内容格式无效");}
        }
        var metadata=new LinkedHashMap<String,Object>();metadata.put("projectId",config.projectId());metadata.put("repository",config.repository());
        metadata.put("endpoint",path);metadata.put("nextPage",response.nextPage());metadata.put("upstreamTruncated",response.truncated());
        metadata.put("dataIsAuthorization",false);metadata.put("capturedPrefixOnly",log);
        var owner=new EvidenceSnapshotStore.Owner(scope.ownerKey(),scope.taskId(),scope.stageId(),scope.attemptId(),UUID.randomUUID().toString());
        var row=snapshots.save(owner,log?"GITLAB_LOG":"GITLAB",config.repository()+path,response.body(),response.truncated()?"TRUNCATED":"COMPLETE",metadata);
        return reads.read(scope.ownerKey(),"9999","snapshot:"+row.id(),number(args,"offset",0));
    }
    private static String path(String name,Map<String,Object> args) {
        String section=text(args,"section");
        if(section!=null&&!Set.of("detail","discussions").contains(section))throw new AssistFailure("GITLAB_SECTION_INVALID","section 只支持 detail 或 discussions");
        String discussions="discussions".equals(section)?"/discussions":"";
        return switch(name) {
            case "gitlab_project_context" -> "";
            case "gitlab_list_issues" -> "/issues";
            case "gitlab_read_issue" -> "/issues/"+positive(args,"iid")+discussions;
            case "gitlab_list_merge_requests" -> "/merge_requests";
            case "gitlab_read_merge_request" -> "/merge_requests/"+positive(args,"iid")+discussions;
            case "gitlab_read_merge_request_diff" -> "/merge_requests/"+positive(args,"iid")+"/diffs";
            case "gitlab_list_pipelines" -> "/pipelines";
            case "gitlab_list_pipeline_jobs" -> "/pipelines/"+positive(args,"pipelineId")+"/jobs";
            case "gitlab_read_job_log" -> "/jobs/"+positive(args,"jobId")+"/trace";
            default -> throw new AssistFailure("ASSIST_TOOL_UNKNOWN","工具不存在");
        };
    }
    private static String pagination(Map<String,Object> args) {
        int page=number(args,"page",1),limit=number(args,"limit",20);
        if(page<1||page>100000||limit<1||limit>50)throw new AssistFailure("GITLAB_PAGE_INVALID","page 必须为正整数，limit 为 1 至 50");
        String result="?page="+page+"&per_page="+limit;
        for(String key:List.of("state","search","ref")) {
            String value=text(args,key);if(value!=null&&!value.isBlank())result+="&"+key+"="+GitLabReadTransport.encode(value);
        }
        return result;
    }
    private static long positive(Map<String,Object> args,String key) {
        long value=args.get(key) instanceof Number n?n.longValue():0;if(value<1)throw new AssistFailure("GITLAB_ID_INVALID","请使用列表返回的 "+key);return value;
    }
    private static int number(Map<String,Object> args,String key,int fallback) {return args.get(key) instanceof Number n?n.intValue():fallback;}
    private static String text(Map<String,Object> args,String key) {return args.get(key) instanceof String s?s:null;}
}
