package io.opencode.loopper.service.workflow;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.WorkflowTestScopeMapper;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.*;
import io.opencode.loopper.template.*;
import io.opencode.loopper.workflow.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Private whole-tree protection is separate from accepted SOURCE and from public CODE delivery. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowTestScopes {
    private final WorkflowTestScopeStore store;
    private final WorkflowTestWorkContract contract;
    private final SourceTreeCapture capture;
    private final WorkflowSourceContent sources;
    private final SourceBuildOutputs builds;
    private final WorkflowCodeSnapshots codes;
    private final ImmutableContentStore originals;
    private final Path data;
    public WorkflowTestScopes(WorkflowTestScopeStore store,WorkflowTestWorkContract contract,SourceTreeCapture capture,WorkflowSourceContent sources,SourceBuildOutputs builds,WorkflowCodeSnapshots codes,LoopperProperties properties) {
        this.store=store;this.contract=contract;this.capture=capture;this.sources=sources;this.builds=builds;this.codes=codes;
        this.data=properties.getDataDir().toAbsolutePath().normalize();this.originals=new ImmutableContentStore(data.resolve("workflow-test-originals"));
    }
    public void prepare(String id) {
        var context=store.context(id);if(!WorkflowTestWrite.supports(context.work().definition().moduleId()))return;
        var fixed=contract.context(context.work().definition(),context.work().inputs()).inputs();Path root=SourcePathPolicy.root(context.workspace().projectDirectory());
        var baseline=store.find(id).orElse(null);
        if(baseline==null) {
            var first=SourceTestTree.scan(root,data);
            var current=capture.capture(new SourceTemplateParameters(root.toString(),fixed.manifest().sourcePath(),null,null,null),true);
            var before=readable(fixed.manifest());var after=readable(current.manifest());
            if(context.workspace().seedSnapshotId()==null) { if(!before.equals(after))throw drift(); }
            else try{SourceTestScope.check(before,after,fixed.frozen().profile(),path->new String(current.contents().get(after.get(path).sha256()),StandardCharsets.UTF_8),path->{var file=fixed.manifest().files().stream().filter(f->f.path().equals(path)).findFirst().orElseThrow(WorkflowCommands::conflict);return new String(sources.read(fixed.frozen().source().snapshotId(),file),StandardCharsets.UTF_8);});}
                catch(TaskFailure changed){throw drift();}
            builds.validate(root,fixed.frozen().profile(),SourceTestProfiles.configurationPaths(fixed.manifest().files()));
            if(!first.equals(SourceTestTree.scan(root,data)))throw drift();
            baseline=store.reserve(context,first);
        }
        var files=store.files(baseline.filesJson());if(!files.equals(SourceTestTree.scan(root,data)))throw drift();
        long total=0;
        for(var entry:files.entrySet())if(SourceTestScope.test(entry.getKey(),fixed.frozen().profile())) {
            var file=entry.getValue();if(!file.kind().equals("FILE")||file.size()>SourceTreeCapture.MAX_FILE_BYTES)throw SourceTestTree.failure("已有测试无法完整冻结，请核对测试文件类型与大小");
            total+=file.size();if(total>SourceTreeCapture.MAX_TOTAL_BYTES)throw SourceTestTree.failure("已有测试正文超过 64 MiB，请缩小项目范围");
            if(originals.find(id,file.sha256(),file.size(),SourceTreeCapture.MAX_FILE_BYTES).isEmpty())originals.write(id,file.sha256(),read(root,entry.getKey(),file),SourceTreeCapture.MAX_FILE_BYTES);
        }
        if(!files.equals(SourceTestTree.scan(root,data)))throw drift();
    }
    public WorkflowTestScopeMapper.Result verify(String id,WorkflowCodeSnapshot.Reference code) {
        var context=store.context(id);if(!WorkflowTestWrite.supports(context.work().definition().moduleId()))return null;
        var saved=store.result(id);if(saved.isPresent())return saved.get();
        if(!context.workspace().state().equals("FROZEN"))throw WorkflowCommands.conflict();
        var fixed=contract.context(context.work().definition(),context.work().inputs()).inputs();
        var baseline=store.find(id).orElseThrow(WorkflowCommands::conflict);var before=store.files(baseline.filesJson());Path root=SourcePathPolicy.root(context.workspace().projectDirectory());
        var live=SourceTestTree.scan(root,data);var manifest=codes.stoppedManifest(id,code);
        var after=new TreeMap<>(live);
        // Git capture stashes the tracked/untracked changes. Reconstruct those exact results from
        // immutable CODE; ignored private files remain live and are still checked against baseline.
        boolean git=context.workspace().objectRepository()==null;
        if(git)for(var change:manifest.changes())if(change.kind().equals("DELETE"))after.remove(change.path());
        for(var file:manifest.files()) {
            if(!SourceTestTree.included(root,file.path(),data))continue;
            var fixedFile=new SourceTestTree.File("FILE",file.sizeBytes(),file.sha256());
            if(!git&&!fixedFile.equals(live.get(file.path())))throw drift();
            after.put(file.path(),fixedFile);
        }
        boolean passed=true;String message="测试文件范围检查通过；实际测试由后续节点执行。";
        try{
            // A mode-only Git change has identical bytes but is still a modification outside test scope.
            var outside=manifest.changes().stream().map(WorkflowCodeSnapshot.Change::path)
                    .filter(path->SourceTestTree.included(root,path,data)&&!SourceTestScope.writable(path,fixed.frozen().profile())).limit(20).toList();
            if(!outside.isEmpty())throw new TaskFailure("SOURCE_TEST_WRITE_RANGE_VIOLATION","固定代码包含测试或夹具范围之外的改动："+String.join("、",outside));
            SourceTestScope.check(before,after,fixed.frozen().profile(),path->manifest.files().stream().filter(f->f.path().equals(path)).findFirst()
                    .map(file->text(codes.readAcceptedFile(manifest,file))).orElseGet(()->text(read(root,path,after.get(path)))),
                    path->{var file=before.get(path);return text(originals.read(id,file.sha256(),file.size(),SourceTreeCapture.MAX_FILE_BYTES));});
            var captured=manifest.files().stream().map(WorkflowCodeSnapshot.File::path).collect(java.util.stream.Collectors.toSet());
            if(after.entrySet().stream().anyMatch(entry->!Objects.equals(before.get(entry.getKey()),entry.getValue())&&!captured.contains(entry.getKey())))
                throw new TaskFailure("SOURCE_TEST_WRITE_RANGE_VIOLATION","部分测试或夹具改动未进入固定代码交付，请核对忽略规则后重新规划。");
        }
        catch(TaskFailure invalid){if(!invalid.code().equals("SOURCE_TEST_WRITE_RANGE_VIOLATION"))throw invalid;passed=false;message=invalid.getMessage();}
        if(!live.equals(SourceTestTree.scan(root,data)))throw drift();
        return store.record(context,after,passed,message);
    }
    private static Map<String,SourceTestTree.File> readable(SourceManifest manifest){var result=new TreeMap<String,SourceTestTree.File>();for(var file:manifest.files())if(file.sha256()!=null)result.put(file.path(),new SourceTestTree.File("FILE",file.sizeBytes(),file.sha256()));return result;}
    private static byte[] read(Path root,String path,SourceTestTree.File expected) {
        Path file=root.resolve(path);SourcePathPolicy.requireContained(root,file);
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=input.readNBytes(SourceTreeCapture.MAX_FILE_BYTES+1);SourcePathPolicy.requireContained(root,file);
            if(bytes.length!=expected.size()||!ImmutableContentStore.hash(bytes).equals(expected.sha256()))throw drift();return bytes;
        }catch(java.io.IOException unavailable){throw SourceTestTree.failure("无法安全读取测试正文，请核对文件和停止状态");}
    }
    private static String text(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException invalid){throw SourceTestTree.failure("测试正文不是有效 UTF-8 文本");}}
    private static ConflictException drift(){return new ConflictException("SOURCE_TEST_INPUT_DRIFT","工作区与本次固定源码或测试基线不一致，请核对原输入及目录；不能沿用旧设计覆盖新内容。");}
}
