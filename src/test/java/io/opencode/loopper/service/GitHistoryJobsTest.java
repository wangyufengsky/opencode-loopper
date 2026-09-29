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

class GitHistoryJobsTest {
    @TempDir Path temporary;
    private Path data,project;
    private LoopperProperties properties;
    private DurableCommands commands;
    private GitHistoryJobs snapshots;
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
    void standaloneHelperRequiresGrantAndCapturesModuleHistoryWithoutSpringLibraries(String format)throws Exception {
        initialize(format);Path module=Files.createDirectories(project.resolve("module"));
        Files.writeString(module.resolve("api.txt"),"original module\n");commit();
        String head=git.read(project,"rev-parse","HEAD").strip();
        Files.writeString(module.resolve("api.txt"),"dirty content\n");
        var input=input(module,null);
        var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);var job=prepare(prepared);
        commands.ensureSupervisor(job,snapshots.environment(input));var registration=registered(job);
        Path directory=Path.of(prepared.request().directory());
        assertThat(directory.resolve("selection")).doesNotExist();assertThat(directory.resolve("history.git")).doesNotExist();
        fresh();commands.grant(job,registration);var receipt=finished(job);

        assertThat(receipt.successful()).as("%s",receipt.output()).isTrue();
        var snapshot=snapshots.read(input,prepared.inputSha256());
        assertThat(snapshot.evidence().head()).isEqualTo(head);
        assertThat(snapshot.binding().prefix()).isEqualTo("module/");
        assertThat(snapshot.evidence().commits().stream().flatMap(c->c.changes().stream()).toList()).extracting(io.opencode.loopper.template.TemplateGitEvidence.Change::path).containsExactly("api.txt");
        assertThat(Files.readString(module.resolve("api.txt"))).isEqualTo("dirty content\n");
        String body=git.read(snapshots.repository(input.nodeId()),"cat-file","blob",snapshot.evidence().commits().getFirst().changes().getFirst().afterBlob());
        assertThat(body).isEqualTo("original module\n");
        assertThat(receipt.children()).noneMatch(Identity::alive);
    }
    @Test void retryAfterStoppedFailureKeepsSelectionEvenWhenBranchMoves()throws Exception {
        initialize("sha1");var input=input(null);String head=git.read(project,"rev-parse","HEAD").strip();
        var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        Path target=snapshots.repository(input.nodeId());Files.createSymbolicLink(target,project);
        var first=prepare(prepared);commands.ensureSupervisor(first);commands.grant(first,registered(first));
        var failed=finished(first);assertThat(failed.stopConfirmed()).isTrue();assertThat(failed.successful()).isFalse();
        Path selection=Path.of(prepared.request().directory()).resolve("selection");byte[] original=GitSnapshotJobProtocol.read(selection);
        assertThat(GitSnapshotJobProtocol.binding(original).commit()).isEqualTo(head);
        Files.delete(target);Files.writeString(project.resolve("service.txt"),"new version\n");commit();
        fresh();var retry=snapshots.prepare(UUID.randomUUID().toString(),input,30);var second=prepare(retry);
        commands.ensureSupervisor(second);commands.grant(second,registered(second));

        assertThat(finished(second).successful()).isTrue();
        assertThat(snapshots.read(input,retry.inputSha256()).evidence().head()).isEqualTo(head);
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
    @Test void changedInputsOrCorruptSnapshotCannotReplaceFrozenEvidence()throws Exception {
        initialize("sha1");var input=input(null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        var job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));assertThat(finished(job).successful()).isTrue();
        var changed=new GitHistoryJobProtocol.Input(input.source(),"2026-09-10","2026-09-11");
        assertThatThrownBy(()->snapshots.prepare(UUID.randomUUID().toString(),changed,30)).hasMessageContaining("记录缺失或不一致");
        assertThatThrownBy(()->snapshots.read(input,"0".repeat(64))).hasMessageContaining("记录缺失或不一致");
        Files.writeString(Path.of(prepared.request().directory()).resolve("evidence"),"broken fixture");
        assertThatThrownBy(()->snapshots.read(input,prepared.inputSha256())).hasMessageContaining("记录缺失或不一致");
    }
    @Test void fullHistoryKeepsNonMonotonicDatesMailmapCoauthorsAndSensitiveExclusions()throws Exception {
        initialize("sha1");
        Files.writeString(project.resolve(".mailmap"),"Canonical <canonical@example.invalid> Fixture <fixture@example.invalid>\n");
        commitAt("2026-09-10T15:59:59Z","map outside range");
        Files.writeString(project.resolve("first.txt"),"first boundary\n");
        String first=commitAt("2026-09-10T16:00:00Z","first\n\nCo-authored-by: Other <other@example.invalid>\n");
        Files.writeString(project.resolve(".env"),"PRIVATE_HISTORY_FIXTURE=withheld\n");
        String last=commitAt("2026-09-11T15:59:59Z","last boundary");
        Files.writeString(project.resolve("outside.txt"),"outside\n");commitAt("2026-09-11T16:00:00Z","outside");
        Files.writeString(project.resolve("old-clock.txt"),"clock moved backwards\n");String tip=commitAt("2026-09-10T01:00:00Z","nonmonotonic tip");
        Files.writeString(project.resolve("first.txt"),"dirty local edits\n");String status=git.read(project,"status","--porcelain=v1","-z"),refs=git.read(project,"show-ref");
        var input=input(null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);

        var evidence=snapshots.read(input,prepared.inputSha256()).evidence();
        assertThat(evidence.head()).isEqualTo(tip);assertThat(evidence.commits()).extracting(io.opencode.loopper.template.TemplateGitEvidence.Commit::sha).contains(first,last).doesNotContain(tip);
        var firstEvidence=evidence.commits().stream().filter(c->c.sha().equals(first)).findFirst().orElseThrow();
        assertThat(firstEvidence.contributors()).extracting(io.opencode.loopper.template.TemplateGitEvidence.Contributor::email).containsExactly("canonical@example.invalid","other@example.invalid");
        assertThat(firstEvidence.author().rawEmail()).isEqualTo("fixture@example.invalid");
        assertThat(evidence.commits().stream().flatMap(c->c.changes().stream()).filter(c->c.path().equals(".env")).toList()).singleElement().satisfies(c->{assertThat(c.patch()).isEmpty();assertThat(c.exclusionReason()).isEqualTo("SENSITIVE_CONTENT_WITHHELD");});
        assertThat(Files.readString(project.resolve("first.txt"))).isEqualTo("dirty local edits\n");
        assertThat(git.read(project,"status","--porcelain=v1","-z")).isEqualTo(status);assertThat(git.read(project,"show-ref")).isEqualTo(refs);
        assertThat(git.read(snapshots.repository(input.nodeId()),"rev-parse","--is-shallow-repository").strip()).isEqualTo("false");
    }
    @Test void emptyDateRangeIsCompleteAndShallowSourceOrPrivateHistoryCannotBeReportedComplete()throws Exception {
        initialize("sha1");Files.writeString(project.resolve("service.txt"),"second\n");commit();
        var empty=new GitHistoryJobProtocol.Input(input(null).source(),"2025-01-01","2025-01-01");
        var prepared=snapshots.prepare(UUID.randomUUID().toString(),empty,30);run(prepared);
        assertThat(snapshots.read(empty,prepared.inputSha256()).evidence().commits()).isEmpty();
        Path shallow=temporary.resolve("shallow");git.read(temporary,"clone","--depth=1","--",project.toUri().toString(),shallow.toString());
        var input=input(shallow,null);prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        var job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));
        var failure=finished(job);assertThat(failure.stopConfirmed()).isTrue();assertThat(failure.successful()).isFalse();assertThat(failure.output()).contains("TEMPLATE_SHALLOW_SOURCE");
        assertThat(snapshots.evidence(input.nodeId())).doesNotExist();
        input=input(null);prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);
        git.read(temporary,"clone","--bare","--depth=1","--",project.toUri().toString(),snapshots.repository(input.nodeId()).toString());
        job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));failure=finished(job);
        assertThat(failure.successful()).isFalse();assertThat(failure.output()).contains("TEMPLATE_SHALLOW_SOURCE");assertThat(snapshots.evidence(input.nodeId())).doesNotExist();
    }
    @Test void frozenTwoParentMergeIsReconstructedAndReverifiedWhileSourceIsOffline()throws Exception {
        initialize("sha1");git.read(project,"switch","-c","feature");Files.writeString(project.resolve("service.txt"),"feature\n");commit();
        git.read(project,"switch","main");Files.writeString(project.resolve("service.txt"),"main\n");commit();
        assertThat(git.run(project,Duration.ofSeconds(10),List.of("merge","--no-ff","feature","-m","merge")).exitCode()).isEqualTo(1);
        Files.writeString(project.resolve("service.txt"),"resolved\n");String merged=commitAt("2026-09-11T02:00:00Z","resolved");
        var input=input(null);var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        var original=snapshots.read(input,prepared.inputSha256());
        var merge=original.evidence().commits().stream().filter(c->c.sha().equals(merged)).findFirst().orElseThrow();
        assertThat(merge.disposition()).isEqualTo("MERGE_RESOLUTION");assertThat(merge.changes()).singleElement().satisfies(c->assertThat(c.patch()).contains("<<<<<<<","+resolved"));
        String sha=GitHistoryEvidenceCodec.hash(snapshots.evidence(input.nodeId()));Files.move(project,temporary.resolve("offline"));
        fresh();prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);run(prepared);
        assertThat(snapshots.read(input,prepared.inputSha256())).isEqualTo(original);assertThat(GitHistoryEvidenceCodec.hash(snapshots.evidence(input.nodeId()))).isEqualTo(sha);
    }
    private void run(GitHistoryJobs.Prepared prepared)throws Exception {
        var job=prepare(prepared);commands.ensureSupervisor(job);commands.grant(job,registered(job));var result=finished(job);
        assertThat(result.successful()).as("%s",result.output()).isTrue();assertThat(result.stopConfirmed()).isTrue();assertThat(result.children()).noneMatch(Identity::alive);
    }
    private void fresh(){commands=new DurableCommands(properties);snapshots=new GitHistoryJobs(properties,credentials);}
    private GitHistoryJobProtocol.Input input(String remote){return input(project,remote);}
    private GitHistoryJobProtocol.Input input(Path root,String remote){return new GitHistoryJobProtocol.Input(new GitSnapshotJobProtocol.Input(UUID.randomUUID().toString(),root.toString(),"refs/heads/main",remote),"2026-09-11","2026-09-11");}
    private DurableCommands.Job prepare(GitHistoryJobs.Prepared prepared){var job=commands.prepare(prepared.request());jobs.add(job);return job;}
    private Registration registered(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).registration()!=null);return commands.observe(job).registration();}
    private Result finished(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).result()!=null&&!commands.observe(job).workerAlive());return commands.observe(job).result();}
    private static void await(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(25);}assertThat(condition.getAsBoolean()).isTrue();}
    private void initialize(String format)throws Exception {git.read(project,"init","-b","main","--template=","--object-format="+format);git.read(project,"config","user.name","Fixture");git.read(project,"config","user.email","fixture@example.invalid");Files.writeString(project.resolve("service.txt"),"original\n");commit();}
    private void commit(){commitAt("2026-09-11T01:00:00Z","fixture");}
    private String commitAt(String date,String message){git.read(project,"add",".");git.run(project,Duration.ofSeconds(10),List.of("commit","-m",message),Map.of("GIT_AUTHOR_DATE",date,"GIT_COMMITTER_DATE",date)).requireSuccess(List.of("commit"));return git.read(project,"rev-parse","HEAD").strip();}
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler)throws IOException {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(command->Thread.ofVirtual().start(command));server.createContext("/",handler);server.start();return server;
    }
    private static String origin(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
}
