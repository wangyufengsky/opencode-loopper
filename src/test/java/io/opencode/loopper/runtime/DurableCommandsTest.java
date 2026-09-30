package io.opencode.loopper.runtime;

import static io.opencode.loopper.runtime.DurableCommandProtocol.*;
import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.config.LoopperProperties;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DurableCommandsTest {
    @TempDir Path data;
    private final List<DurableCommands.Job> jobs = new ArrayList<>();
    private DurableCommands commands;
    @BeforeEach void setup() throws Exception { data = data.toRealPath(); commands = fresh(); }
    @AfterEach void stopOwnedFixtures() throws Exception {
        for (var job : jobs) {
            try { commands.requestStop(job); } catch (RuntimeException ignored) { }
            for (var name : List.of("process", "process-preparation-0", "process-preparation-1", "process-preparation-2", "process-preparation-3", "registration")) {
                Path file = directory(job).resolve(name);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                var identity = registration(read(file)).worker();
                ProcessHandle.of(identity.pid()).filter(identity::matches).ifPresent(handle -> {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly); handle.destroyForcibly();
                });
            }
        }
    }
    @Test void nothingRunsUntilTheDurableIdentityIsGrantedAndRestartReadsTheSameResult() throws Exception {
        var job = prepare("normal", 10); commands.ensureSupervisor(job);
        var registered = registered(job); assertThat(Files.exists(data.resolve("effects"))).isFalse();
        assertThat(commands.observe(job).result()).isNull();
        commands = fresh(); assertThat(commands.observe(job).registration()).isEqualTo(registered);
        commands.grant(job, registered); commands.grant(job, registered); commands.ensureSupervisor(job);
        var result = finished(job);
        assertThat(result.exitCode()).isZero(); assertThat(result.stopConfirmed()).isTrue(); assertThat(result.launched()).isTrue();
        assertThat(result.output()).contains("检查通过"); assertThat(result.requestSha256()).isEqualTo(job.requestSha256());
        assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
        assertThat(fresh().observe(job).result()).isEqualTo(result);
    }
    @Test void concurrentLostSupervisorAcknowledgementReplaysNeverLaunchTheCommandTwice() throws Exception {
        var job = prepare("normal", 10);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> commands.ensureSupervisor(job)));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        commands.grant(job, registered(job)); assertThat(finished(job).stopConfirmed()).isTrue();
        commands.ensureSupervisor(job); assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
    }
    @Test void cancellationBeforeGrantHasAnExplicitNoLaunchReceipt() throws Exception {
        var job = prepare("normal", 10); commands.requestStop(job); commands.ensureSupervisor(job);
        var result = finished(job); assertThat(result.cancelled()).isTrue(); assertThat(result.launched()).isFalse();
        assertThat(result.stopConfirmed()).isTrue(); assertThat(Files.exists(data.resolve("effects"))).isFalse();
    }
    @Test void anActualApplicationJvmCanExitWhileTheSameSupervisorFinishesItsOriginalCommand() throws Exception {
        var job = prepare("delayed", 10);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var application = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Application.class.getName(), data.toString(), job.id(), job.requestSha256())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(application.waitFor(8, TimeUnit.SECONDS)).isTrue(); assertThat(application.exitValue()).isZero();
            assertThat(commands.observe(job).workerAlive()).isTrue(); assertThat(commands.observe(job).result()).isNull();
            commands = fresh(); var result = finished(job); assertThat(result.exitCode()).isZero(); assertThat(result.stopConfirmed()).isTrue();
            assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
        } finally { if (application.isAlive()) application.destroyForcibly(); }
    }
    @Test void timeoutStopsTheObservedDescendantsAndKeepsTheEvidence() throws Exception {
        var job = prepare("child", 1); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var result = finished(job); assertThat(result.timedOut()).isTrue(); assertThat(result.stopConfirmed()).isTrue();
        assertThat(result.children()).hasSizeGreaterThanOrEqualTo(2); assertThat(result.children()).noneMatch(Identity::alive);
        assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
    }
    @Test void explicitStopAfterAClientRestartRetainsTheOriginalAttempt() throws Exception {
        var job = prepare("sleep", 30); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        await(() -> Files.exists(data.resolve("effects"))); commands = fresh(); commands.requestStop(job);
        var result = finished(job); assertThat(result.cancelled()).isTrue(); assertThat(result.stopConfirmed()).isTrue();
        assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
    }
    @Test void outputOverflowIsBoundedAndCannotBeAcceptedAsACompleteResult() throws Exception {
        var job = prepare("noise", 10); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var result = finished(job); assertThat(result.outputTruncated()).isTrue(); assertThat(result.stopConfirmed()).isTrue();
        assertThat(result.output().length()).isLessThanOrEqualTo(OUTPUT_LIMIT);
    }
    @Test void aDeadSupervisorWithoutItsReceiptNeverAuthorizesRerunningTheCommand() throws Exception {
        var job = prepare("sleep", 30); commands.ensureSupervisor(job); var registered = registered(job); commands.grant(job, registered);
        await(() -> Files.exists(directory(job).resolve("process")) && Files.exists(data.resolve("effects")));
        var worker = ProcessHandle.of(registered.worker().pid()).orElseThrow(); assertThat(registered.worker().matches(worker)).isTrue(); worker.destroyForcibly();
        await(() -> !registered.worker().alive()); commands = fresh(); assertThat(commands.observe(job).stopUnknown()).isTrue();
        commands.ensureSupervisor(job); assertThat(commands.observe(job).registration()).isEqualTo(registered);
        assertThat(commands.observe(job).result()).isNull(); assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(1);
    }
    @Test void changedRequestsCorruptReceiptsAndLinksCannotReplaceFrozenEvidence() throws Exception {
        var job = prepare("normal", 10); var request = request(read(directory(job).resolve("request")));
        assertThatThrownBy(() -> commands.prepare(new Request(request.id(), request.directory(), request.argv(), 11))).hasMessageContaining("执行记录无法准备");
        Files.writeString(directory(job).resolve("result"), "broken");
        assertThatThrownBy(() -> commands.observe(job)).hasMessageContaining("记录缺失或不一致");
        var other = data.resolve("other"); Files.createDirectory(other); var linked = UUID.randomUUID().toString();
        Files.createSymbolicLink(data.resolve("workflow-commands").resolve(linked), other);
        assertThatThrownBy(() -> commands.prepare(new Request(linked, request.directory(), request.argv(), 10))).hasMessageContaining("执行记录无法准备");
        try (var files = Files.list(other)) { assertThat(files.toList()).isEmpty(); }
    }
    @Test void preparationRunsInOrderBeforeTheMainCommandAndRestartKeepsAllReceipts() throws Exception {
        var job = prepare("normal", 10, "prepare-one", "prepare-two"); commands.ensureSupervisor(job);
        var registered = registered(job); assertThat(Files.exists(data.resolve("effects"))).isFalse();
        commands.grant(job, registered); var receipt = finished(job);
        assertThat(receipt.successful()).isTrue(); assertThat(receipt.preparations()).hasSize(2).allMatch(Result::successful);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("prepare-one", "prepare-two", "normal");
        for (int i = 0; i < 2; i++) assertThat(result(read(directory(job).resolve("preparation-" + i + "-result")))).isEqualTo(receipt.preparations().get(i));
        commands = fresh(); commands.ensureSupervisor(job); assertThat(commands.observe(job).result()).isEqualTo(receipt);
        assertThat(Files.readAllLines(data.resolve("effects"))).hasSize(3);
    }
    @Test void failedPreparationHasItsOwnExitCodeAndPreventsAllLaterEffects() throws Exception {
        var job = prepare("normal", 10, "failed", "prepare-two"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var receipt = finished(job); assertThat(receipt.launched()).isFalse(); assertThat(receipt.stopConfirmed()).isTrue();
        assertThat(receipt.exitCode()).isNull(); assertThat(receipt.error()).isEqualTo("COMMAND_PREPARATION_FAILED");
        assertThat(receipt.preparations()).hasSize(1); assertThat(receipt.preparations().getFirst().exitCode()).isEqualTo(7);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("failed");
        assertThat(directory(job).resolve("process")).doesNotExist();
    }
    @Test void timeoutDuringPreparationStopsItsDescendantsAndPreventsTheMainCommand() throws Exception {
        var job = prepare("normal", 1, "child"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var receipt = finished(job); assertThat(receipt.timedOut()).isTrue(); assertThat(receipt.launched()).isFalse();
        assertThat(receipt.stopConfirmed()).isTrue(); assertThat(receipt.preparations().getFirst().children()).hasSizeGreaterThanOrEqualTo(2).noneMatch(Identity::alive);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("child");
    }
    @Test void cancellationDuringPreparationAfterRestartDoesNotRunRemainingSteps() throws Exception {
        var job = prepare("normal", 30, "sleep", "prepare-two"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        await(() -> Files.exists(data.resolve("effects"))); commands = fresh(); commands.requestStop(job);
        var receipt = finished(job); assertThat(receipt.cancelled()).isTrue(); assertThat(receipt.stopConfirmed()).isTrue(); assertThat(receipt.launched()).isFalse();
        assertThat(receipt.preparations()).hasSize(1); assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("sleep");
    }
    @Test void globalTimeoutIncludesEarlierPreparationsInsteadOfResettingForTheNextOne() throws Exception {
        var job = prepare("normal", 4, "short-delay", "short-delay"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var receipt = finished(job); assertThat(receipt.timedOut()).isTrue(); assertThat(receipt.launched()).isFalse(); assertThat(receipt.stopConfirmed()).isTrue();
        assertThat(receipt.preparations()).hasSize(2); assertThat(receipt.preparations().getFirst().successful()).isTrue();
        assertThat(receipt.preparations().get(1).timedOut()).isTrue();
        // A shared deadline can stop the second process before its main method writes the marker.
        assertThat(Files.readAllLines(data.resolve("effects"))).hasSizeBetween(1, 2).allMatch("short-delay"::equals);
    }
    @Test void outputBudgetIsSharedAcrossPreparationsInsteadOfResetForEveryProcess() throws Exception {
        var job = prepare("normal", 10, "bounded-noise", "bounded-noise"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var receipt = finished(job); assertThat(receipt.outputTruncated()).isTrue(); assertThat(receipt.launched()).isFalse(); assertThat(receipt.stopConfirmed()).isTrue();
        assertThat(receipt.preparations()).hasSize(2); assertThat(receipt.preparations().getFirst().successful()).isTrue();
        assertThat(receipt.preparations().stream().mapToInt(step -> step.output().length()).sum()).isLessThanOrEqualTo(OUTPUT_LIMIT);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("bounded-noise", "bounded-noise");
    }
    @Test void malformedOutputCannotExpandTheSavedUtf8BeyondItsSharedBudget() throws Exception {
        var job = prepare("normal", 10, "invalid-utf8"); commands.ensureSupervisor(job); commands.grant(job, registered(job));
        var receipt = finished(job); assertThat(receipt.launched()).isFalse(); assertThat(receipt.outputTruncated()).isTrue(); assertThat(receipt.stopConfirmed()).isTrue();
        assertThat(receipt.preparations().getFirst().output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(OUTPUT_LIMIT);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("invalid-utf8");
    }
    @Test void preparationWithoutAFinalReceiptKeepsStopUnknownAndNeverReplays() throws Exception {
        var job = prepare("normal", 30, "sleep"); commands.ensureSupervisor(job); var registered = registered(job); commands.grant(job, registered);
        await(() -> Files.exists(directory(job).resolve("process-preparation-0")) && Files.exists(data.resolve("effects")));
        var worker = ProcessHandle.of(registered.worker().pid()).orElseThrow(); assertThat(registered.worker().matches(worker)).isTrue(); worker.destroyForcibly();
        await(() -> !registered.worker().alive()); commands = fresh(); assertThat(commands.observe(job).stopUnknown()).isTrue(); commands.ensureSupervisor(job);
        assertThat(commands.observe(job).result()).isNull(); assertThat(commands.observe(job).registration()).isEqualTo(registered);
        assertThat(Files.readAllLines(data.resolve("effects"))).containsExactly("sleep"); assertThat(directory(job).resolve("process")).doesNotExist();
    }
    private DurableCommands fresh() { var properties = new LoopperProperties(); properties.setDataDir(data); return new DurableCommands(properties); }
    private Path directory(DurableCommands.Job job) { return data.resolve("workflow-commands").resolve(job.id()); }
    private DurableCommands.Job prepare(String mode, int seconds, String... preparations) throws Exception {
        Path workspace = data.resolve("workspace"); Files.createDirectories(workspace);
        var steps = new ArrayList<Preparation>();
        for (int i = 0; i < preparations.length; i++) steps.add(new Preparation("PREPARE_" + i, workspace.toString(), fixtureArgv(preparations[i])));
        var request = new Request(UUID.randomUUID().toString(), workspace.toRealPath().toString(), fixtureArgv(mode), seconds, steps);
        var job = commands.prepare(request); jobs.add(job); return job;
    }
    private List<String> fixtureArgv(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String classes = Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, Fixture.class.getName(), mode, data.resolve("effects").toString());
    }
    private Registration registered(DurableCommands.Job job) throws Exception { await(() -> commands.observe(job).registration() != null); return commands.observe(job).registration(); }
    private Result finished(DurableCommands.Job job) throws Exception {
        await(() -> commands.observe(job).result() != null && !commands.observe(job).workerAlive()); return commands.observe(job).result();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(25); }
        assertThat(condition.getAsBoolean()).as("bounded supervisor condition").isTrue();
    }
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("descendant")) { Thread.sleep(60_000); return; }
            Files.writeString(Path.of(args[1]), args[0] + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (args[0].equals("failed")) System.exit(7);
            if (args[0].equals("bounded-noise")) System.out.print("x".repeat(40000));
            if (args[0].equals("invalid-utf8")) { byte[] bytes = new byte[40000]; Arrays.fill(bytes, (byte) 0xff); System.out.write(bytes); }
            if (args[0].equals("child")) {
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "descendant", args[1]).start();
                child.waitFor(); Thread.sleep(60_000);
            }
            if (args[0].equals("sleep")) Thread.sleep(60_000);
            if (args[0].equals("delayed")) Thread.sleep(3000);
            if (args[0].equals("short-delay")) Thread.sleep(2400);
            if (args[0].equals("noise")) for (int i = 0; i < 20; i++) System.out.print("x".repeat(8192));
            System.out.write("检查通过\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public static class Application {
        public static void main(String[] args) throws Exception {
            var properties = new LoopperProperties(); properties.setDataDir(Path.of(args[0]));
            var commands = new DurableCommands(properties); var job = new DurableCommands.Job(args[1], args[2]); commands.ensureSupervisor(job);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (commands.observe(job).registration() == null && System.nanoTime() < deadline) Thread.sleep(25);
            commands.grant(job, commands.observe(job).registration());
            while (!Files.exists(Path.of(args[0], "effects")) && System.nanoTime() < deadline) Thread.sleep(25);
            if (!Files.exists(Path.of(args[0], "effects"))) throw new IllegalStateException("Command did not begin");
        }
    }
}
