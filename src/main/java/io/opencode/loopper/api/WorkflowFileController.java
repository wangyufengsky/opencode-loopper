package io.opencode.loopper.api;

import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.workflow.WorkflowCodeFiles;
import io.opencode.loopper.workflow.WorkflowCodeSnapshot;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/requirements/{id}/nodes/{key}/attempts/{attemptId}/{direction}/{name}")
public final class WorkflowFileController {
    private final WorkflowCodeFiles files;
    public WorkflowFileController(WorkflowCodeFiles files) { this.files=files; }
    @GetMapping("/files") public CursorPage<WorkflowCodeFiles.File> list(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @PathVariable String direction,@PathVariable String name,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return files.list(binding(id,key,attemptId,direction,name),cursor,limit);
    }
    @GetMapping("/changes") public CursorPage<WorkflowCodeSnapshot.Change> changes(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @PathVariable String direction,@PathVariable String name,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {
        return files.changes(binding(id,key,attemptId,direction,name),cursor,limit);
    }
    @GetMapping("/file") public ResponseEntity<byte[]> download(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @PathVariable String direction,@PathVariable String name,@RequestParam String path) {
        var bytes=files.bytes(binding(id,key,attemptId,direction,name),path);
        String filename=path.substring(path.lastIndexOf('/')+1);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    @GetMapping("/text") public WorkflowCodeFiles.Text text(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @PathVariable String direction,@PathVariable String name,@RequestParam String path,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="12000") int limit) {
        return files.text(binding(id,key,attemptId,direction,name),path,offset,limit);
    }
    private WorkflowCodeFiles.Binding binding(String id,String key,String attempt,String direction,String name) {
        return switch(direction) {
            case "inputs" -> files.input(id,key,attempt,name);
            case "outputs" -> files.output(id,key,attempt,name);
            default -> throw new BadRequestException("WORKFLOW_FILE_BINDING_INVALID","请从节点输入或交付物选择文件");
        };
    }
    @GetMapping("/archive") public ResponseEntity<byte[]> archive(@PathVariable String id,@PathVariable String key,@PathVariable String attemptId,
            @PathVariable String direction,@PathVariable String name) {
        byte[] bytes=files.archive(binding(id,key,attemptId,direction,name));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("流程文档.zip",StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
}
