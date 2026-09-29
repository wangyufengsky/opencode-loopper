package io.opencode.loopper.api;

import io.opencode.loopper.service.*;
import io.opencode.loopper.service.workflow.*;
import io.opencode.loopper.workflow.WorkflowUpload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/documents")
public final class WorkflowUploadController {
    private final WorkflowUploads uploads;
    private final WorkflowCodeFiles files;
    public WorkflowUploadController(WorkflowUploads uploads,WorkflowCodeFiles files){this.uploads=uploads;this.files=files;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkflowUpload.Summary upload(@PathVariable String id,
            @RequestHeader(value="X-Loopper-Local-UI",required=false) String authority,
            @RequestPart("metadata") WorkflowUploadStore.Request request,@RequestPart("files") List<MultipartFile> incoming) throws IOException {
        WorkflowTemplateController.requireLocalUi(authority);
        if(incoming.isEmpty()||incoming.size()>10)throw new BadRequestException("DOCUMENT_TEMPLATE_FILES_REQUIRED","请上传 1–10 份需求文档");
        long total=0;
        for(var file:incoming){if(file.getSize()<1||file.getSize()>DocumentTemplateStorage.MAX_FILE_BYTES)throw new BadRequestException("DOCUMENT_TEMPLATE_FILE_SIZE","单份文档不能为空或超过 20 MiB");total+=file.getSize();}
        if(total>DocumentTemplateStorage.MAX_BATCH_BYTES)throw new BadRequestException("DOCUMENT_TEMPLATE_BATCH_SIZE","本次文档总大小超过 50 MiB");
        var values=new ArrayList<DocumentTemplateStorage.Incoming>();
        for(var file:incoming)values.add(new DocumentTemplateStorage.Incoming(file.getOriginalFilename(),file.getBytes()));
        return uploads.upload(id,request,values);
    }
    @GetMapping public CursorPage<WorkflowUpload.Summary> list(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit){return uploads.list(id,cursor,limit);}
    @GetMapping("/{upload}") public WorkflowUpload.Summary get(@PathVariable String id,@PathVariable String upload){return uploads.get(id,upload);}
    @GetMapping("/{upload}/files") public CursorPage<WorkflowCodeFiles.File> list(@PathVariable String id,@PathVariable String upload,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit){return files.list(binding(id,upload),cursor,limit);}
    @GetMapping("/{upload}/text") public WorkflowCodeFiles.Text text(@PathVariable String id,@PathVariable String upload,@RequestParam String path,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="12000") int limit){return files.text(binding(id,upload),path,offset,limit);}
    @GetMapping("/{upload}/file") public ResponseEntity<byte[]> file(@PathVariable String id,@PathVariable String upload,@RequestParam String path){
        var bytes=files.bytes(binding(id,upload),path);String name=path.substring(path.lastIndexOf('/')+1);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition",ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    private WorkflowCodeFiles.Binding binding(String id,String upload){return files.uploaded(id,uploads.get(id,upload).reference());}
}
