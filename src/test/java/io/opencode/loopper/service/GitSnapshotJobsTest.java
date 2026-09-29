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

class GitSnapshotJobsTest {
    @TempDir Path temporary;
    private Path data,project;
    private LoopperProperties properties;
    private DurableCommands commands;
    private GitSnapshotJobs snapshots;
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
    void standaloneHelperRequiresGrantAndCapturesOnlySelectedModuleWithoutSpringLibraries(String format)throws Exception {
        initialize(format);Path module=Files.createDirectories(project.resolve("module"));
        Files.writeString(module.resolve("api.txt"),"original module\n");commit();
        String head=git.read(project,"rev-parse","HEAD").strip();
        Files.writeString(module.resolve("api.txt"),"dirty content\n");
        var input=new GitSnapshotJobProtocol.Input(UUID.randomUUID().toString(),module.toString(),"refs/heads/main",null);
        var prepared=snapshots.prepare(UUID.randomUUID().toString(),input,30);var job=prepare(prepared);
        commands.ensureSupervisor(job,snapshots.environment(input));var registration=registered(job);
        Path directory=Path.of(prepared.request().directory());
        assertThat(directory.resolve("selection")).doesNotExist();assertThat(directory.resolve("code.git")).doesNotExist();
        fresh();commands.grant(job,registration);var receipt=finished(job);

        assertThat(receipt.successful()).as("%s",receipt.output()).isTrue();
        var snapshot=snapshots.read(input,prepared.inputSha256());
        assertThat(snapshot.snapshot().commitSha()).isEqualTo(head);
        assertThat(snapshot.snapshot().projectPrefix()).isEqualTo("module/");
        assertThat(snapshot.snapshot().files()).extracting(GitSnapshotInventory.Entry::path).containsExactly("api.txt");
        assertThat(Files.readString(module.resolve("api.txt"))).isEqualTo("dirty content\n");
        String body=git.read(snapshots.repository(input.nodeId()),"cat-file","blob",snapshot.snapshot().files().getFirst().blobSha());
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
        assertThat(snapshots.read(input,retry.inputSha256()).snapshot().commitSha()).isEqualTo(head);
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
        var changed=new GitSnapshotJobProtocol.Input(input.nodeId(),project.toString(),"refs/heads/other",null);
        assertThatThrownBy(()->snapshots.prepare(UUID.randomUUID().toString(),changed,30)).hasMessageContaining("记录缺失或不一致");
        assertThatThrownBy(()->snapshots.read(input,"0".repeat(64))).hasMessageContaining("记录缺失或不一致");
        Files.writeString(Path.of(prepared.request().directory()).resolve("snapshot"),"broken fixture");
        assertThatThrownBy(()->snapshots.read(input,prepared.inputSha256())).hasMessageContaining("记录缺失或不一致");
    }
    private void fresh(){commands=new DurableCommands(properties);snapshots=new GitSnapshotJobs(properties,credentials);}
    private GitSnapshotJobProtocol.Input input(String remote){return new GitSnapshotJobProtocol.Input(UUID.randomUUID().toString(),project.toString(),"refs/heads/main",remote);}
    private DurableCommands.Job prepare(GitSnapshotJobs.Prepared prepared){var job=commands.prepare(prepared.request());jobs.add(job);return job;}
    private Registration registered(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).registration()!=null);return commands.observe(job).registration();}
    private Result finished(DurableCommands.Job job)throws Exception {await(()->commands.observe(job).result()!=null&&!commands.observe(job).workerAlive());return commands.observe(job).result();}
    private static void await(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(25);}assertThat(condition.getAsBoolean()).isTrue();}
    private void initialize(String format)throws Exception {git.read(project,"init","-b","main","--template=","--object-format="+format);git.read(project,"config","user.name","Fixture");git.read(project,"config","user.email","fixture@example.invalid");Files.writeString(project.resolve("service.txt"),"original\n");commit();}
    private void commit(){git.read(project,"add",".");git.read(project,"commit","-m","fixture");}
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler)throws IOException {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(command->Thread.ofVirtual().start(command));server.createContext("/",handler);server.start();return server;
    }
    private static String origin(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
}
