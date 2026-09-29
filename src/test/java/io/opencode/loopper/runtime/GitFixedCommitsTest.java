package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.service.ConflictException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GitFixedCommitsTest {
    @TempDir Path temporary;
    final GitEvidenceProcess git=new GitEvidenceProcess(new SafeProcessRunner());
    final GitFixedCommits commits=new GitFixedCommits(git);
    private static final String DATE="2026-09-29T00:00:00.123Z";

    @ParameterizedTest @ValueSource(strings={"sha1","sha256"})
    void createsExactPinnedCommitAndRefWithoutReadingDirtyFilesOrChangingIndexOrHead(String format)throws Exception {
        Path repo=repository(format);String parent=git.read(repo,"rev-parse","HEAD").strip(),tree=git.read(repo,"rev-parse","HEAD^{tree}").strip();
        Files.writeString(repo.resolve("code.txt"),"later dirty work");Files.writeString(repo.resolve("staged.txt"),"unrelated staged file");
        git.read(repo,"add","--","staged.txt");Files.writeString(repo.resolve("untracked.txt"),"untracked work");
        byte[] index=Files.readAllBytes(repo.resolve(".git/index"));String status=git.read(repo,"status","--porcelain=v1","-z");
        var intent=new GitCommitIntent(tree,parent,"#3032_固定成果\n","作者名字","author@example.test",DATE);
        String commit=commits.create(repo,intent);assertThat(commits.create(repo,intent)).isEqualTo(commit);
        assertThat(git.read(repo,"cat-file","commit",commit)).isEqualTo(new String(GitFixedCommits.content(intent),StandardCharsets.UTF_8));
        String ref="refs/heads/loopper/publication-123";commits.reference(repo,ref,commit);commits.reference(repo,ref,commit);
        assertThat(git.read(repo,"rev-parse",ref).strip()).isEqualTo(commit);
        assertThat(git.read(repo,"symbolic-ref","HEAD").strip()).isEqualTo("refs/heads/main");assertThat(git.read(repo,"rev-parse","HEAD").strip()).isEqualTo(parent);
        assertThat(Files.readAllBytes(repo.resolve(".git/index"))).isEqualTo(index);assertThat(git.read(repo,"status","--porcelain=v1","-z")).isEqualTo(status);
        assertThat(git.read(repo,"show",commit+":code.txt")).isEqualTo("original\n");
        assertThat(git.run(repo,Duration.ofSeconds(5),List.of("cat-file","-e",commit+":staged.txt")).exitCode()).isNotZero();
        assertThat(git.run(repo,Duration.ofSeconds(5),List.of("cat-file","-e",commit+":untracked.txt")).exitCode()).isNotZero();
        assertThat(Files.readString(repo.resolve("code.txt"))).isEqualTo("later dirty work");
    }
    @Test void legacyParentlessBaselineKeepsExactlyTheSameObjectIdentity()throws Exception {
        Path repo=repository("sha1");String tree=git.read(repo,"rev-parse","HEAD^{tree}").strip();String body="Workflow directory baseline legacy-owner\n";
        Map<String,String> env=new HashMap<>();for(String role:List.of("AUTHOR","COMMITTER")) {
            env.put("GIT_"+role+"_NAME","Loopper");env.put("GIT_"+role+"_EMAIL","workflow@loopper.invalid");env.put("GIT_"+role+"_DATE","@1790640000 +0000");
        }
        var args=List.of("commit-tree",tree);var old=git.input(repo,Duration.ofSeconds(5),args,env,body.getBytes(StandardCharsets.UTF_8));old.requireSuccess(args);
        String actual=commits.create(repo,new GitCommitIntent(tree,null,body,"Loopper","workflow@loopper.invalid",DATE));
        assertThat(actual).isEqualTo(new String(old.output(),StandardCharsets.US_ASCII).strip());
    }
    @Test void simultaneousRecoveryCreatesOnlyTheSameRefAndRefusesForeignOrSymbolicRefs()throws Exception {
        Path repo=repository("sha1");String parent=git.read(repo,"rev-parse","HEAD").strip(),tree=git.read(repo,"rev-parse","HEAD^{tree}").strip();
        String commit=commits.create(repo,new GitCommitIntent(tree,parent,"Saved workflow\n","User","user@example.test",DATE));String ref="refs/heads/loopper/recovered";
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++)futures.add(executor.submit(()->{try{gate.await();commits.reference(repo,ref,commit);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            gate.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);
        }
        assertThat(git.read(repo,"rev-parse",ref).strip()).isEqualTo(commit);
        assertThatThrownBy(()->commits.reference(repo,ref,parent)).isInstanceOf(ConflictException.class);
        git.read(repo,"symbolic-ref","refs/heads/loopper/symbolic","refs/heads/main");
        assertThatThrownBy(()->commits.reference(repo,"refs/heads/loopper/symbolic",parent)).isInstanceOf(ConflictException.class);
        git.read(repo,"symbolic-ref","refs/heads/loopper/dangling","refs/heads/missing");
        assertThatThrownBy(()->commits.reference(repo,"refs/heads/loopper/dangling",commit)).isInstanceOf(ConflictException.class);
        assertThat(git.read(repo,"symbolic-ref","refs/heads/loopper/dangling").strip()).isEqualTo("refs/heads/missing");
        assertThat(git.read(repo,"rev-parse",ref).strip()).isEqualTo(commit);
    }
    @Test void danglingSymbolicRefAppearingAfterPreflightIsRejectedUnderPreparedGitLock()throws Exception {
        Path repo=repository("sha1");String parent=git.read(repo,"rev-parse","HEAD").strip();
        String ref="refs/heads/loopper/race";var racing=spy(git);
        doAnswer(call->{
            git.read(repo,"symbolic-ref",ref,"refs/heads/missing");return call.callRealMethod();
        }).when(racing).createReference(eq(repo),any(),eq(ref),eq(parent),anyMap(),any());
        assertThatThrownBy(()->new GitFixedCommits(racing).reference(repo,ref,parent)).isInstanceOf(ConflictException.class);
        assertThat(git.read(repo,"symbolic-ref",ref).strip()).isEqualTo("refs/heads/missing");
        assertThat(repo.resolve(".git/refs/heads/loopper/race.lock")).doesNotExist();
        assertThat(git.run(repo,Duration.ofSeconds(5),List.of("rev-parse","--verify","refs/heads/missing")).exitCode()).isNotZero();
    }
    @Test void preparedLockPreventsAConcurrentSymbolicReplacement()throws Exception {
        Path repo=repository("sha1");String parent=git.read(repo,"rev-parse","HEAD").strip();String ref="refs/heads/loopper/locked";
        var result=git.createReference(repo,Duration.ofSeconds(10),ref,parent,Map.of(),()->{
            var replacement=git.run(repo,Duration.ofSeconds(3),List.of("symbolic-ref",ref,"refs/heads/missing"));
            assertThat(replacement.exitCode()).isNotZero();assertThat(git.run(repo,Duration.ofSeconds(3),List.of("symbolic-ref","--quiet",ref)).exitCode()).isNotZero();
        });
        result.requireSuccess(List.of("update-ref"));assertThat(git.read(repo,"rev-parse",ref).strip()).isEqualTo(parent);
        assertThat(repo.resolve(".git/refs/heads/loopper/locked.lock")).doesNotExist();
    }
    @Test void timedOutGuardAbortsPreparedReferenceAndOriginalOperationCanRetry()throws Exception {
        Path repo=repository("sha1");String parent=git.read(repo,"rev-parse","HEAD").strip(),ref="refs/heads/loopper/slow-guard";
        var entered=new java.util.concurrent.atomic.AtomicBoolean();long started=System.nanoTime();
        assertThatThrownBy(()->git.createReference(repo,Duration.ofMillis(500),ref,parent,Map.of(),()->{
            entered.set(true);
            try{new CountDownLatch(1).await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("interrupted guard",interrupted);}
        })).isInstanceOfSatisfying(io.opencode.loopper.domain.TaskFailure.class,error->assertThat(error.code()).isEqualTo("TEMPLATE_GIT_TIMEOUT"));
        assertThat(entered).isTrue();assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));
        assertThat(repo.resolve(".git/refs/heads/loopper/slow-guard.lock")).doesNotExist();
        assertThat(git.run(repo,Duration.ofSeconds(3),List.of("rev-parse","--verify",ref)).exitCode()).isNotZero();
        commits.reference(repo,ref,parent);assertThat(git.read(repo,"rev-parse",ref).strip()).isEqualTo(parent);
    }
    @Test void metadataIsValidatedBeforeIoAndTransactionsCannotStartGitWork() {
        var unused=mock(GitEvidenceProcess.class);var subject=new GitFixedCommits(unused);
        for(var intent:List.of(new GitCommitIntent("1".repeat(40),null,"valid\n","bad\nname","email",DATE),
                new GitCommitIntent("1".repeat(40),null,"invalid\0\n","name","email",DATE),
                new GitCommitIntent("1".repeat(40),null,"invalid\uD800\n","name","email",DATE),
                new GitCommitIntent("1".repeat(40),"2".repeat(64),"valid\n","name","email",DATE),
                new GitCommitIntent("1".repeat(40),null,"valid\n","name","email","yesterday")))
            assertThatThrownBy(()->subject.create(temporary,intent)).isInstanceOf(ConflictException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->subject.create(temporary,null)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->subject.reference(temporary,"refs/heads/test","1".repeat(40))).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(unused);
    }
    private Path repository(String format)throws Exception {
        Path root=Files.createDirectory(temporary.resolve("repo-"+format));git.read(root,"init","--quiet","--initial-branch=main","--object-format="+format);
        git.read(root,"config","user.name","Fixture");git.read(root,"config","user.email","fixture@example.test");git.read(root,"config","commit.gpgSign","false");git.read(root,"config","core.autocrlf","false");
        Files.writeString(root.resolve("code.txt"),"original\n");git.read(root,"add","--","code.txt");git.read(root,"commit","-qm","baseline");return root;
    }
}
