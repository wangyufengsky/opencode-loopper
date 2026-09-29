package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.workflow.*;
import io.opencode.loopper.runtime.ImmutableContentStore;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowDirectoryChangesTest {
    @Test void preservesUnrelatedAdditionsModificationsAndDeletions() {
        var baseline=snapshot(file("work","old"),file("notes","old"),file("removed-by-user","old"),file("delete","old"));
        var result=snapshot(file("work","new"),file("notes","old"),file("removed-by-user","old"),file("added","new"));
        var current=snapshot(file("work","old"),file("notes","user"),file("user-file","keep"),file("delete","old"));
        var plan=WorkflowDirectoryChanges.plan(baseline,result,current);
        assertThat(plan.conflicts()).isEmpty();assertThat(plan.after().files()).containsExactly(file("added","new"),file("notes","user"),file("user-file","keep"),file("work","new"));
        assertThat(List.of(plan.added(),plan.modified(),plan.deleted(),plan.preservedChanges())).containsExactly(1,1,1,3);
    }
    @Test void conflictingChangesIncludeModeMissingOriginalAndCollidingNewFiles() {
        var original=file("code","old");var mode=new WorkflowCodeSnapshot.File("code","100755",original.blob(),original.sha256(),original.sizeBytes());
        for(var current:List.of(snapshot(file("code","user")),snapshot(),snapshot(mode)))
            assertThat(WorkflowDirectoryChanges.plan(snapshot(original),snapshot(file("code","new")),current).conflicts()).containsExactly("code");
        assertThat(WorkflowDirectoryChanges.plan(snapshot(),snapshot(file("new","result")),snapshot(file("new","user"))).conflicts()).containsExactly("new");
        assertThat(WorkflowDirectoryChanges.plan(snapshot(original),snapshot(),snapshot(file("code","user"))).after()).isNull();
    }
    @Test void alreadyAppliedPathsAreIdempotentAndFileDirectoryChangesRejectUnrelatedChildren() {
        var before=snapshot(file("file","before"));var after=snapshot(file("file/child","after"));
        assertThat(WorkflowDirectoryChanges.plan(before,after,before).after()).isEqualTo(after);
        assertThat(WorkflowDirectoryChanges.plan(before,after,after).added()).isZero();
        var reverse=WorkflowDirectoryChanges.plan(after,before,snapshot(file("file/child","after"),file("file/user","user")));
        assertThat(reverse.conflicts()).containsExactly("file","file/user");assertThat(reverse.after()).isNull();
        assertThat(WorkflowDirectoryChanges.plan(snapshot(),snapshot(file("dir/child","new")),snapshot(file("dir","user"))).conflicts()).containsExactly("dir","dir/child");
    }
    @Test void malformedManifestAndDifferentDirectoryIdentityAreRejected() {
        assertThatThrownBy(()->WorkflowDirectoryChanges.plan(snapshot(),snapshot(),new WorkflowDirectorySnapshot(1,"/other","fingerprint",List.of()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->WorkflowDirectoryChanges.plan(snapshot(file(".env","secret")),snapshot(),snapshot())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->WorkflowDirectoryChanges.plan(snapshot(file("a","a"),file("a/child","b")),snapshot(),snapshot())).isInstanceOf(RuntimeException.class);
    }
    static WorkflowDirectorySnapshot snapshot(WorkflowCodeSnapshot.File...files){return new WorkflowDirectorySnapshot(1,"/directory","fingerprint",List.of(files));}
    static WorkflowCodeSnapshot.File file(String path,String text){byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);return new WorkflowCodeSnapshot.File(path,"100644",GitCodeSnapshots.blobId(bytes,40),ImmutableContentStore.hash(bytes),bytes.length);}
}
