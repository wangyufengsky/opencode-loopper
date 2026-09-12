package io.opencode.loopper.service.assist;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.service.DesignerAttachmentReadService;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssistDocumentService {
    private final AssistDocumentParser parser;
    private final DesignerAttachmentReadService attachments;
    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final ExecutorService workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon().name("assist-document-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public AssistDocumentService(AssistDocumentParser parser,DesignerAttachmentReadService attachments,LoopperMapper mapper,ObjectMapper json) {
        this.parser=parser;this.attachments=attachments;this.mapper=mapper;this.json=json;
    }
    public record Source(String name,String sha256,String representationSha,String parserVersion,AssistDocumentParser.Document document) { }
    public Source load(AssistScopeService.Scope scope,String reference,String expected) {
        if(reference==null)throw new AssistFailure("DOCUMENT_SOURCE_REQUIRED","请传入 workspace:相对路径 或 attachment:附件ID");
        if(reference.startsWith("attachment:"))return attachment(scope,reference.substring(11),expected);
        if(!reference.startsWith("workspace:"))throw new AssistFailure("DOCUMENT_SOURCE_INVALID","文档引用必须来自工作区或已冻结附件");
        Path path=AssistFiles.resolve(scope.directory(),reference.substring(10));
        byte[] bytes=AssistFiles.read(path,20*1024*1024);String hash=AssistFiles.sha(bytes);check(hash,expected);
        Future<AssistDocumentParser.Document> job;
        try {job=workers.submit(()->parser.parse(path.getFileName().toString(),bytes));}
        catch(RejectedExecutionException e){throw new AssistFailure("DOCUMENT_BUSY","文档解析容量已满，请稍后重试","WAIT");}
        try {var parsed=job.get(15,TimeUnit.SECONDS);
            return new Source(path.getFileName().toString(),hash,AssistFiles.sha(json.writeValueAsBytes(parsed)),AssistDocumentParser.VERSION,parsed);
        }catch(TimeoutException e){job.cancel(true);throw new AssistFailure("DOCUMENT_PARSE_TIMEOUT","解析超过 15 秒，请拆分文件","FIX_INPUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();job.cancel(true);throw new AssistFailure("DOCUMENT_INTERRUPTED","文档解析已中断","WAIT");}
        catch(ExecutionException e){if(e.getCause() instanceof AssistFailure safe)throw safe;throw new AssistFailure("DOCUMENT_READ_FAILED","文档无法解析，请检查格式和完整性");}
    }
    private Source attachment(AssistScopeService.Scope scope,String id,String expected) {
        String hash,version;DesignerAttachmentReadService.Preview preview;
        if(scope.taskId()!=null) {
            var row=mapper.findTaskDesignAttachment(id).filter(r->r.taskId().equals(scope.taskId())).orElseThrow(AssistDocumentService::denied);
            hash=row.sha256();version=row.extractorId()+":"+row.extractorVersion();preview=attachments.previewForTask(scope.taskId(),id);
        }else {
            var row=mapper.findDesignerAttachment(id).filter(r->Objects.equals(r.designerSessionId(),scope.designerId())&&"ACTIVE".equals(r.state())).orElseThrow(AssistDocumentService::denied);
            hash=row.sha256();version=row.extractorId()+":"+row.extractorVersion();preview=attachments.previewForSession(scope.designerId(),id);
        }
        check(hash,expected);if(preview.text()==null)throw new AssistFailure("DOCUMENT_TEXT_UNAVAILABLE","此附件没有已冻结文本，不支持 OCR");
        byte[] frozen=preview.text().getBytes(StandardCharsets.UTF_8);
        return new Source(preview.filename(),hash,AssistFiles.sha(frozen),"FROZEN:"+version,parser.parse("snapshot.md",frozen));
    }
    public Map<String,Object> inspect(Source source,int offset) {
        var all=source.document().sections();if(offset<0||offset>all.size())throw new AssistFailure("DOCUMENT_CURSOR_INVALID","请使用目录返回的游标");
        var result=identity(source);
        result.put("sections",all.stream().skip(offset).limit(100).map(s->Map.of("section",Integer.parseInt(s.id()),"title",s.title(),"characters",s.markdown().length())).toList());
        result.put("sectionCount",all.size());result.put("nextOffset",offset+100<all.size()?offset+100:-1);return result;
    }
    public Map<String,Object> read(Source source,int section) {
        var all=source.document().sections();if(section<0||section>=all.size())throw new AssistFailure("DOCUMENT_SECTION_INVALID","分段不存在，请先调用 inspect_document 获取目录");
        var result=identity(source);result.put("section",section);result.put("markdown",all.get(section).markdown());result.put("nextSection",section+1<all.size()?section+1:-1);return result;
    }
    private Map<String,Object> identity(Source source) {
        var result=new LinkedHashMap<String,Object>();result.put("name",source.name());result.put("sha256",source.sha256());
        result.put("representationSha256",source.representationSha());result.put("parserVersion",source.parserVersion());
        String name=source.name();result.put("format",source.parserVersion().startsWith("FROZEN:")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):source.document().format());
        result.put("representationFormat","MARKDOWN");result.put("limitations",source.document().limitations());return result;
    }
    static void check(String actual,String expected){if(expected!=null&&!expected.equals(actual))throw new AssistFailure("DOCUMENT_SOURCE_CHANGED","源文件已改变，请重新读取并确认最新内容");}
    private static AssistFailure denied(){return new AssistFailure("DOCUMENT_SCOPE_DENIED","附件不属于当前作用域","REAUTHORIZE");}
    @PreDestroy public void close(){workers.shutdownNow();}
}
