package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GitPublicationTransportTest {
    @TempDir Path directory;
    Path local,remote;String commit,tree;GitPushProtocol.Input input;
    final GitEvidenceProcess git=spy(new GitEvidenceProcess(new SafeProcessRunner()));
    final GitPublicationTransport transport=new GitPublicationTransport(git);
    @BeforeEach void setup()throws Exception {
        local=Files.createDirectory(directory.resolve("local"));remote=directory.resolve("remote.git");run(local,"init","-q","--initial-branch=main");run(directory,"init","--bare","-q",remote.toString());
        Files.writeString(local.resolve("code.txt"),"fixed");run(local,"add","-A");run(local,"-c","user.name=Fixture","-c","user.email=fixture@localhost","commit","-qm","fixed");commit=run(local,"rev-parse","HEAD").strip();tree=run(local,"rev-parse","HEAD^{tree}").strip();
        run(local,"update-ref","refs/heads/loopper/results/fixed",commit);run(local,"remote","add","origin",remote.toString());input=new GitPushProtocol.Input(UUID.randomUUID().toString(),local.toRealPath().toString(),local.toRealPath().toString(),transport.gitDirectory(local),"origin",transport.target(local,"origin"),"loopper/results/fixed",commit,tree);
    }
    @Test void createsOnlyTheExactRemoteRefAndReplayDoesNotPushAgain()throws Exception {
        Files.writeString(local.resolve("user.txt"),"unrelated");run(local,"add","user.txt");String index=run(local,"write-tree"),status=run(local,"status","--porcelain");
        assertThat(transport.observe(input)).isNull();transport.publish(input);transport.publish(input);assertThat(transport.observe(input)).isEqualTo(commit);
        assertThat(run(remote,"for-each-ref","--format=%(refname)").lines()).containsExactly("refs/heads/loopper/results/fixed");
        assertThat(run(local,"write-tree")).isEqualTo(index);assertThat(run(local,"status","--porcelain")).isEqualTo(status);assertThat(run(local,"symbolic-ref","--short","HEAD").strip()).isEqualTo("main");
        verify(git,times(1)).remote(eq(local.toRealPath()),eq(local.toRealPath()),eq(Duration.ofSeconds(120)),argThat(args->args.contains("push")&&!args.contains("--force")&&args.contains("--force-with-lease=refs/heads/loopper/results/fixed:")),eq(remote.toString()));
    }
    @Test void anotherRemoteCommitIsNeverReplacedEvenWhenItWouldBeAFastForward() {
        run(local,"push",remote.toString(),commit+":refs/heads/loopper/results/fixed");
        String next=run(local,"-c","user.name=Other","-c","user.email=other@local","commit-tree",tree,"-p",commit,"-m","next").strip();run(local,"update-ref","refs/heads/loopper/results/fixed",next);
        var newer=new GitPushProtocol.Input(input.id(),input.project(),input.repository(),input.gitDirectory(),input.remote(),input.url(),input.branch(),next,tree);
        assertThatThrownBy(()->transport.publish(newer)).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);assertThat(run(remote,"rev-parse","refs/heads/loopper/results/fixed").strip()).isEqualTo(commit);
    }
    @Test void atomicEmptyLeaseRejectsARefCreatedAfterTheAdvertisement() {
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{var args=call.<List<String>>getArgument(3);if(args.contains("push")&&changed.compareAndSet(false,true)){run(remote,"update-ref","refs/heads/loopper/results/fixed",commit);String other=run(remote,"-c","user.name=Other","-c","user.email=other@local","commit-tree",tree,"-m","raced").strip();run(remote,"update-ref","refs/heads/loopper/results/fixed",other);}return call.callRealMethod();}).when(git).remote(any(),any(),any(),anyList(),anyString());
        // Make the baseline objects available without the result ref.
        run(local,"push",remote.toString(),commit+":refs/heads/base");
        assertThatThrownBy(()->transport.publish(input)).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);assertThat(run(remote,"rev-parse","refs/heads/loopper/results/fixed").strip()).isNotEqualTo(commit);
    }
    @Test void targetChangesMultiplePushUrlsAndCredentialBearingUrlsFailBeforeNetwork() {
        run(local,"remote","set-url","--push","origin",directory.resolve("different.git").toString());assertThatThrownBy(()->transport.publish(input)).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);
        run(local,"remote","set-url","--push","--add","origin",remote.toString());assertThatThrownBy(()->transport.target(local,"origin")).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);
        for(var url:List.of("https://user:secret@example.test/repo","https://example.test/repo?secret=1","ext::bad","-upload-pack","https://example.test/repo\nother"))assertThatThrownBy(()->GitPublicationTransport.validateUrl(url)).isInstanceOf(io.opencode.loopper.domain.TaskFailure.class);
        verify(git,never()).remote(any(),any(),any(),anyList(),anyString());
    }
    @Test void wireFormatRejectsDifferentOrIncompleteIdentity()throws Exception {
        assertThat(GitPushProtocol.decode(GitPushProtocol.encode(input))).isEqualTo(input);byte[] bytes=GitPushProtocol.encode(input);
        assertThatThrownBy(()->GitPushProtocol.decode(Arrays.copyOf(bytes,bytes.length-1))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->new GitPushProtocol.Input(input.id(),input.project(),input.repository(),input.gitDirectory(),input.remote(),input.url(),"main",commit,tree)).isInstanceOf(IllegalArgumentException.class);
    }
    private String run(Path cwd,String...args){var result=new GitEvidenceProcess(new SafeProcessRunner()).run(cwd,Duration.ofSeconds(10),List.of(args));result.requireSuccess(List.of(args));return result.output();}
}
