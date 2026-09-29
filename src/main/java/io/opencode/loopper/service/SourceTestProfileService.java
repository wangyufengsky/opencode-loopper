package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Resolves native test conventions from the frozen manifest without installing frameworks or rewriting build files. */
@Service
public final class SourceTestProfileService {
    private final SourceTemplateMapper runs;
    private final LoopperMapper domain;
    private final SourceSnapshotStorage storage;
    private final SourceTemplateAdmission admission;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final SourceBuildOutputs buildOutputs;
    private final SourceTestProfiles profiles;
    public SourceTestProfileService(SourceTemplateMapper runs, LoopperMapper domain, SourceSnapshotStorage storage,
            SourceTemplateAdmission admission, TransactionTemplate transactions, ObjectMapper json, SourceBuildOutputs buildOutputs, SourceTestProfiles profiles) {
        this.runs = runs; this.domain = domain; this.storage = storage;
        this.admission = admission; this.transactions = transactions; this.json = json; this.buildOutputs = buildOutputs;this.profiles=profiles;
    }
    public SourceTestProfile prepare(SourceTemplateRunRow run) {
        if (domain.sourceTestProfile(run.id()).isPresent()) return require(run.id());
        var parameters = json.readValue(run.parametersJson(), SourceTemplateParameters.class);
        var profile = resolve(parameters, json.readValue(run.snapshotJson(), SourceSnapshot.class).manifestSha256(),
                runs.files(run.id()), sha -> storage.read(run.id(), sha));
        String body = json.writeValueAsString(profile);
        transactions.executeWithoutResult(ignored -> {
            var current = admission.require(run.id());
            if (!current.state().equals("PREPARING") || current.version() != run.version()) throw SourceTemplateAdmission.conflict();
            if (domain.insertSourceTestProfile(new SourceDevelopmentContextMapper.TestProfile(run.id(), body,
                    DocumentModelStore.hash(body), Instant.now().toString())) != 1) throw SourceTemplateAdmission.conflict();
        });
        return profile;
    }
    public SourceTestProfile preview(SourceTemplateParameters parameters, SourceTreeCapture.Capture captured) {
        var files = new ArrayList<SourceTemplateMapper.File>();
        for (var file : captured.manifest().files()) files.add(new SourceTemplateMapper.File("preview", files.size(), file.path(),
                file.target() ? 1 : 0, file.sizeBytes(), file.sha256(), file.exclusion()));
        return resolve(parameters, captured.manifest().sha256(), files,
                sha -> new String(captured.contents().get(sha), java.nio.charset.StandardCharsets.UTF_8));
    }
    private SourceTestProfile resolve(SourceTemplateParameters parameters, String manifestSha, List<SourceTemplateMapper.File> all,
            java.util.function.Function<String,String> reader) {
        var files=all.stream().map(file->new SourceManifest.File(file.path(),file.target()==1,file.sizeBytes(),file.sha256(),file.exclusion())).toList();
        SourceTestProfile profile;
        try{profile=profiles.resolve(manifestSha,parameters.sourcePath(),parameters.testOutputPath(),files,reader);}
        catch(BadRequestException invalid){throw new BadRequestException(invalid.code(),invalid.getMessage()+"。模板不会修改依赖或构建文件；确认项目测试入口后重新预检。已冻结的任务只能使用原配置，修改配置后请取消原任务并重新发起。");}
        buildOutputs.validate(SourcePathPolicy.root(parameters.projectRoot()),profile,SourceTestProfiles.configurationPaths(files));
        return profile;
    }
    public SourceTestProfile require(String run) {
        var row = domain.sourceTestProfile(run).orElseThrow(() -> SourceTestProfiles.missing(run, "测试配置尚未冻结"));
        if (!DocumentModelStore.hash(row.profileJson()).equals(row.sha256())) throw SourceTemplateAdmission.conflict();
        return json.readValue(row.profileJson(), SourceTestProfile.class);
    }
}
