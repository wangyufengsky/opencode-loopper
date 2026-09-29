package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitReviewJobsTest {
    @TempDir Path temporary;
    private Path data,project;
    private LoopperProperties properties;
    private DurableCommands commands;
    private GitReviewJobs snapshots;
    private final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    private final List<DurableCommands.Job> jobs=new ArrayList<>();
    private GitCredentialProvider credentials=(root,url)->Map.of();
    @BeforeEach void setup()throws Exception {
        temporary=temporary.toRealPath();data=temporary.resolve("data");project=Files.createDirectory(temporary.resolve("project"));
        properties=new LoopperProperties();properties.setDataDir(data);fresh();
    }
    @AfterEach void stopFixtures()throws Exception {
        for(var job:jobs) {
            try{commands.requestStop(job);}catch(RuntimeException ignored) { }
            for(String name:List.of("process","registration")) {
                Path file=data.resolve("workflow-commands").resolve(job.id()).resolve(name);
                if(!Files.isRegularFile(file))continue;
                var identity=DurableCommandProtocol.registration(DurableCommandProtocol.read(file)).worker();
                ProcessHandle.of(identity.pid()).filter(identity::matches).ifPresent(handle->{handle.descendants().forEach(ProcessHandle::destroyForcibly);handle.destroyForcibly();});
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"sha1","sha256"})
    void grantedStandaloneHelperCapturesOnlyFixedModuleWithVerifiedInitialReferences(String format)throws Exception {
        initialize(format);Path module=Files.createDirectories(project.resolve("module"));
        Files.writeString(module.resolve("api.txt"),"original module\n");
        Files.writeString(module.resolve(".env"),"PRIVATE_SNAPSHOT_FIXTURE=withheld\n");
        Files.createDirectories(module.resolve("target"));Files.writeString(module.resolve("target/generated.txt"),"generated\n");commit();
        String head=git.read(project,"rev-parse","HEAD").strip();
        Files.writeString(module.resolve("api.txt"),"dirty content\n");String status=git.read(project,"status","--porcelain=v1","-z"),refs=git.read(project,"show-ref");
        var input=input(module,null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);var job=prepare(prepared);
        commands.ensureSupervisor(job,snapshots.environment(input));var registration=registered(job);
        Path directory=Path.of(prepared.request().directory());assertThat(directory.resolve("selection")).doesNotExist();assertThat(directory.resolve("review.git")).doesNotExist();
        fresh();commands.grant(job,registration);var receipt=finished(job);assertThat(receipt.successful()).as("%s",receipt.output()).isTrue();
        assertThat(receipt.stopConfirmed()).isTrue();assertThat(receipt.children()).noneMatch(Identity::alive);
        var frozen=snapshots.read(input,prepared.inputSha256());var snapshot=frozen.snapshot();
        assertThat(snapshot.sourceSha()).isEqualTo(head);assertThat(snapshot.targetSha()).isEqualTo(head);assertThat(snapshot.baselineSha()).isNull();
        assertThat(frozen.selection().binding().prefix()).isEqualTo("module/");
        assertThat(snapshot.files()).extracting(io.opencode.loopper.template.SnapshotReview.File::path).containsExactly(".env","api.txt","target/generated.txt");
        var api=snapshot.units().stream().filter(u->u.path().equals("api.txt")).findFirst().orElseThrow();
        assertThat(api.excerpt()).contains("original module").doesNotContain("dirty content");
        assertThat(api.initialEvidence()).singleElement().satisfies(r->{assertThat(r.version()).isEqualTo(head);assertThat(r.quote()).isEqualTo("original module");assertThat(r.startLine()).isEqualTo(1);});
        assertThat(snapshot.units().stream().filter(u->!u.path().equals("api.txt"))).allSatisfy(u->{assertThat(u.excerpt()).isEmpty();assertThat(u.limitation()).isNotBlank();assertThat(u.initialEvidence()).isEmpty();});
        assertThat(Files.readString(module.resolve("api.txt"))).isEqualTo("dirty content\n");
        assertThat(git.read(project,"status","--porcelain=v1","-z")).isEqualTo(status);assertThat(git.read(project,"show-ref")).isEqualTo(refs);
    }
    @Test void dateSelectionComparesFinalTreesAndPreservesDeletedRenamedAndNewPaths()throws Exception {
        initialize("sha1");Files.writeString(project.resolve("rename.txt"),"unchanged content for rename\n");
        Files.writeString(project.resolve("delete.txt"),"deleted line\n");String baseline=commitAt("2026-09-10T15:59:59Z","baseline");
        Files.move(project.resolve("rename.txt"),project.resolve("renamed.txt"));Files.delete(project.resolve("delete.txt"));
        Files.writeString(project.resolve("service.txt"),"replacement\n");Files.writeString(project.resolve("new.txt"),"added line\n");
        String target=commitAt("2026-09-11T15:59:59Z","included end day");
        Files.writeString(project.resolve("outside.txt"),"not in final selected tree\n");String source=commitAt("2026-09-11T16:00:00Z","next day");
        var input=incremental(input(null),"2026-09-11","2026-09-11");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        var snapshot=snapshots.read(input,prepared.inputSha256()).snapshot();
        assertThat(snapshot.sourceSha()).isEqualTo(source);assertThat(snapshot.baselineSha()).isEqualTo(baseline);assertThat(snapshot.targetSha()).isEqualTo(target);
        assertThat(snapshot.startInclusive()).isEqualTo("2026-09-10T16:00:00Z");assertThat(snapshot.endExclusive()).isEqualTo("2026-09-11T16:00:00Z");
        assertThat(snapshot.selectionBasis()).isEqualTo("FIRST_PARENT_COMMITTER_TIME");assertThat(snapshot.noChanges()).isFalse();
        assertThat(snapshot.units()).extracting(io.opencode.loopper.template.SnapshotReview.Unit::path).contains("delete.txt","renamed.txt","new.txt","service.txt").doesNotContain("outside.txt");
        var deleted=snapshot.units().stream().filter(u->u.path().equals("delete.txt")).findFirst().orElseThrow();
        assertThat(deleted.initialEvidence()).anySatisfy(r->{assertThat(r.version()).isEqualTo(baseline);assertThat(r.quote()).isEqualTo("deleted line");});
        var renamed=snapshot.units().stream().filter(u->u.path().equals("renamed.txt")).findFirst().orElseThrow();
        assertThat(renamed.change()).startsWith("R");assertThat(renamed.beforePath()).isEqualTo("rename.txt");
    }
    @Test void nonMonotonicFirstParentSelectionNeverStopsAtAnOlderClockAndSameTreesNeedNoAnalysis()throws Exception {
        initialize("sha1");String baseline=commitAt("2026-09-10T15:59:59Z","baseline");
        String source=commitAt("2026-09-12T01:00:00Z","same tree");
        var input=incremental(input(null),"2026-09-11","2026-09-29");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        var snapshot=snapshots.read(input,prepared.inputSha256()).snapshot();
        assertThat(snapshot.sourceSha()).isEqualTo(source);assertThat(snapshot.nonMonotonic()).isTrue();
        assertThat(snapshot.noChanges()).isTrue();assertThat(snapshot.units()).isEmpty();assertThat(snapshot.baselineSha()).isEqualTo(baseline);
    }
    @Test void absentModuleAtBaselineIsEmptyTreeAndDoesNotExposeRepositorySiblings()throws Exception {
        initialize("sha1");String baseline=commitAt("2026-09-10T15:59:59Z","before module");
        Path module=Files.createDirectory(project.resolve("module"));Files.writeString(module.resolve("api.txt"),"only module\n");String target=commitAt("2026-09-11T00:00:00Z","module created");
        var input=incremental(input(module,null),"2026-09-11","2026-09-11");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        var snapshot=snapshots.read(input,prepared.inputSha256()).snapshot();
        assertThat(snapshot.baselineSha()).isEqualTo(baseline);assertThat(snapshot.targetSha()).isEqualTo(target);
        assertThat(snapshot.files()).singleElement().satisfies(f->assertThat(f.path()).isEqualTo("api.txt"));
        assertThat(snapshot.units()).singleElement().satisfies(u->{assertThat(u.change()).isEqualTo("A");assertThat(u.excerpt()).contains("only module");});
    }
    @Test void missingBoundaryAndShallowHistoryFailWithoutFallingBackToFullReview()throws Exception {
        initialize("sha1");var input=incremental(input(null),"2020-01-01","2020-01-02");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        var job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));var result=finished(job);
        assertThat(result.successful()).isFalse();assertThat(result.stopConfirmed()).isTrue();assertThat(result.output()).contains("SNAPSHOT_BOUNDARY_MISSING");assertThat(snapshots.evidence(input.nodeId())).doesNotExist();
        Path shallow=temporary.resolve("shallow");git.read(temporary,"clone","--depth=1","--",project.toUri().toString(),shallow.toString());
        input=input(shallow,null);prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);job=prepare(prepared);
        commands.ensureSupervisor(job);commands.grant(job,registered(job));result=finished(job);
        assertThat(result.successful()).isFalse();assertThat(result.stopConfirmed()).isTrue();assertThat(result.output()).contains("TEMPLATE_SHALLOW_SOURCE");assertThat(snapshots.evidence(input.nodeId())).doesNotExist();
    }
    @Test void changedInputsCorruptEvidenceAndPathAliasesCannotReplaceCapturedSource()throws Exception {
        initialize("sha1");var input=input(null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        var changed=incremental(input,"2026-09-11","2026-09-11");
        assertThatThrownBy(()->snapshots.prepare(UUID.randomUUID().toString(),changed,30)).hasMessageContaining("记录缺失或不一致");
        assertThatThrownBy(()->snapshots.read(input,"0".repeat(64))).hasMessageContaining("记录缺失或不一致");
        Path evidence=snapshots.evidence(input.nodeId());Files.writeString(evidence,"broken fixture");
        assertThatThrownBy(()->snapshots.read(input,prepared.inputSha256())).hasMessageContaining("记录缺失或不一致");
        Files.delete(evidence);Path outside=temporary.resolve("external");Files.writeString(outside,"unrelated");Files.createSymbolicLink(evidence,outside);
        assertThatThrownBy(()->snapshots.read(input,prepared.inputSha256())).hasMessageContaining("记录缺失或不一致");assertThat(Files.readString(outside)).isEqualTo("unrelated");
    }
    private static GitReviewJobProtocol.Input incremental(GitReviewJobProtocol.Input input,String start,String end) {
        return new GitReviewJobProtocol.Input(input.source(),input.projectId(),io.opencode.loopper.template.SnapshotReview.Mode.DATE_INCREMENTAL,start,end);
    }
    @Test void retryAfterStoppedFailureKeepsSelectionEvenWhenBranchMoves()throws Exception {
        initialize("sha1");var input=input(null);String head=git.read(project,"rev-parse","HEAD").strip();
        var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        Path target=snapshots.repository(input.nodeId());Files.createSymbolicLink(target,project);
        var first=prepare(prepared);commands.ensureSupervisor(first);commands.grant(first,registered(first));
        var failed=finished(first);assertThat(failed.stopConfirmed()).isTrue();assertThat(failed.successful()).isFalse();
        Path selection=Path.of(prepared.request().directory()).resolve("selection");byte[] original=GitSnapshotJobProtocol.read(selection);
        assertThat(GitReviewJobProtocol.selection(original).binding().commit()).isEqualTo(head);
        Files.delete(target);Files.writeString(project.resolve("service.txt"),"new version\n");commit();
        fresh();var retry=snapshots.prepare(UUID.randomUUID().toString(),input,30);var second=prepare(retry);
        commands.ensureSupervisor(second);commands.grant(second,registered(second));

        assertThat(finished(second).successful()).isTrue();
        assertThat(snapshots.read(input,retry.inputSha256()).snapshot().sourceSha()).isEqualTo(head);
        assertThat(GitSnapshotJobProtocol.read(selection)).containsExactly(original);
    }
    @Test void originalObjectsCanBeReverifiedWhileSourceIsOffline()throws Exception {
        initialize("sha1");git.read(project,"remote","add","origin",project.toString());var input=input("origin");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        var job=prepare(prepared);commands.ensureSupervisor(job,snapshots.environment(input));commands.grant(job,registered(job));assertThat(finished(job).successful()).isTrue();
        var original=snapshots.read(input,prepared.inputSha256());Files.move(project,temporary.resolve("offline"));
        credentials=new GitCredentialProvider() {
            public Map<String,String> environment(Path root,String remote){throw new AssertionError("Captured data must not request credentials");}
            public Scope scope(Path root){throw new AssertionError("Captured data must not resolve an offline source or decrypt credentials");}
        };
        fresh();var retry=snapshots.prepare(UUID.randomUUID().toString(),input,30);job=prepare(retry);
        commands.ensureSupervisor(job,snapshots.environment(input));commands.grant(job,registered(job));

        assertThat(finished(job).successful()).isTrue();assertThat(snapshots.read(input,retry.inputSha256())).isEqualTo(original);
    }
    @Test void cancellationBeforeGrantHasNoGitSelectionAndAnExplicitStopReceipt()throws Exception {
        initialize("sha1");var input=input(null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        var job=prepare(prepared);commands.requestStop(job);commands.ensureSupervisor(job);
        var result=finished(job);
        assertThat(result.launched()).isFalse();assertThat(result.stopConfirmed()).isTrue();assertThat(result.cancelled()).isTrue();
        assertThat(Path.of(prepared.request().directory()).resolve("selection")).doesNotExist();
    }
    @Test void cancellingRemoteReadAfterClientRestartStopsHelperAndGitDescendants()throws Exception {
        initialize("sha1");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        HttpServer server=server(exchange->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}exchange.sendResponseHeaders(503,-1);exchange.close();});
        try {
            git.read(project,"remote","add","origin",origin(server)+"/repository.git");var input=input("origin");
            var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);var job=prepare(prepared);
            commands.ensureSupervisor(job);commands.grant(job,registered(job));assertThat(entered.await(8,TimeUnit.SECONDS)).isTrue();
            fresh();commands.requestStop(job);var result=finished(job);

            assertThat(result.cancelled()).isTrue();assertThat(result.stopConfirmed()).isTrue();
            assertThat(result.children()).hasSizeGreaterThanOrEqualTo(2).noneMatch(Identity::alive);
            assertThatThrownBy(()->snapshots.read(input,prepared.inputSha256())).hasMessageContaining("记录缺失或不一致");
        }finally{release.countDown();server.stop(0);}
    }
    @Test void authenticatedRemoteUsesOnlyEphemeralEnvironmentAndPersistsNoCredential()throws Exception {
        initialize("sha1");String secret="snapshot-fixture-secret",encoded=Base64.getEncoder().encodeToString(("fixture:"+secret).getBytes(StandardCharsets.UTF_8));
        var authorization=new AtomicReference<String>();
        HttpServer server=server(exchange->{authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.sendResponseHeaders(503,-1);exchange.close();});
        try {
            String origin=origin(server);git.read(project,"remote","add","origin",origin+"/repository.git");
            credentials=new GitCredentialProvider() {
                public Map<String,String> environment(Path root,String remote){throw new AssertionError("The helper must use a scoped grant");}
                public Scope scope(Path root){assertThat(root).isEqualTo(project);return new Scope(origin,true,GitHttpAuthentication.environment(origin,"fixture",secret,origin));}
            };
            fresh();var input=input("origin");var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);var job=prepare(prepared);
            commands.ensureSupervisor(job,snapshots.environment(input));commands.grant(job,registered(job));var result=finished(job);

            assertThat(authorization.get()).isEqualTo("Basic "+encoded);assertThat(result.successful()).isFalse();
            assertThat(result.output()).contains("TEMPLATE_GIT_REMOTE_UNAVAILABLE").doesNotContain(secret,encoded);
            try(var files=Files.walk(data)) {
                for(Path file:files.filter(Files::isRegularFile).toList())
                    assertThat(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1)).as("%s",file).doesNotContain(secret,encoded);
            }
        }finally{server.stop(0);}
    }
    private void run(GitReviewJobs.Prepared prepared)throws Exception {
        var job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));var result=finished(job);
        assertThat(result.successful()).as("%s",result.output()).isTrue();assertThat(result.stopConfirmed()).isTrue();assertThat(result.children()).noneMatch(Identity::alive);
    }
    private void fresh(){commands=new DurableCommands(properties);snapshots=new GitReviewJobs(properties,credentials);}
    private GitReviewJobProtocol.Input input(String remote){return input(project,remote);}
    private GitReviewJobProtocol.Input input(Path root,String remote){return new GitReviewJobProtocol.Input(new GitSnapshotJobProtocol.Input(UUID.randomUUID().toString(),root.toString(),"refs/heads/main",remote),"fixture-project",io.opencode.loopper.template.SnapshotReview.Mode.FULL,null,null);}
    private DurableCommands.Job prepare(GitReviewJobs.Prepared prepared){var job=commands.prepare(prepared.request());jobs.add(job);return job;}
    private Registration registered(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).registration()!=null);return commands.observe(job).registration();}
    private Result finished(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).result()!=null&&!commands.observe(job).workerAlive());return commands.observe(job).result();}
    private static void await(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(25);}assertThat(condition.getAsBoolean()).isTrue();}
    private void initialize(String format)throws Exception {git.read(project,"init","-b","main","--template=","--object-format="+format);git.read(project,"config","user.name","Fixture");git.read(project,"config","user.email","fixture@example.invalid");Files.writeString(project.resolve("service.txt"),"original\n");commit();}
    private void commit(){commitAt("2026-09-11T01:00:00Z","fixture");}
    private String commitAt(String date,String message){git.read(project,"add",".");git.run(project,Duration.ofSeconds(10),List.of("commit","--allow-empty","-m",message),Map.of("GIT_AUTHOR_DATE",date,"GIT_COMMITTER_DATE",date)).requireSuccess(List.of("commit"));return git.read(project,"rev-parse","HEAD").strip();}
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler)throws IOException {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(command->Thread.ofVirtual().start(command));server.createContext("/",handler);server.start();return server;
    }
    private static String origin(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
}
