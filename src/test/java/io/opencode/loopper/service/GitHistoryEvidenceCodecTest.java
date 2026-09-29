package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.template.TemplateGitEvidence;
import io.opencode.loopper.template.TemplateGitEvidence.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHistoryEvidenceCodecTest {
    @TempDir Path temporary;
    @org.junit.jupiter.api.BeforeEach void canonicalDirectory()throws IOException {temporary=temporary.toRealPath();}
    @Test void completeEvidenceRoundTripsWithoutLosingNullableIdentityOrPatchAndCannotBeReplaced()throws Exception {
        var input=input();var value=value(input,"引用\t文件.java","@@ -1 +1 @@\n-原文\n+新文\n");
        Path file=temporary.resolve("evidence");String hash=GitHistoryEvidenceCodec.publish(file,value);
        assertThat(GitHistoryEvidenceCodec.read(file)).isEqualTo(value);
        assertThat(hash).isEqualTo(GitHistoryEvidenceCodec.hash(file));assertThat(GitHistoryEvidenceCodec.publish(file,value)).isEqualTo(hash);
        assertThatThrownBy(()->GitHistoryEvidenceCodec.publish(file,value(input,"another.java","changed"))).isInstanceOf(IOException.class).hasMessageContaining("differs");
        assertThat(GitHistoryEvidenceCodec.read(file)).isEqualTo(value);
        try(var files=Files.list(temporary)){assertThat(files.filter(p->p.getFileName().toString().endsWith(".part")).toList()).isEmpty();}
    }
    @Test void datesMustBeExplicitAndEvidenceMustMatchBranchRangeAndFrozenInput()throws Exception {
        var input=input();assertThat(GitHistoryJobProtocol.input(GitHistoryJobProtocol.input(input))).isEqualTo(input);
        for(String date:List.of("","2026-02-30","2026-9-11","1899-09-11"))
            assertThatThrownBy(()->new GitHistoryJobProtocol.Input(input.source(),date,"2026-09-11")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new GitHistoryJobProtocol.Input(input.source(),"2026-09-12","2026-09-11")).isInstanceOf(IllegalArgumentException.class);
        var frozen=value(input,"api.java","patch");String sha=frozen.binding().inputSha256();GitHistoryJobProtocol.requireBinding(input,sha,frozen);
        var changed=new GitHistoryJobProtocol.Input(input.source(),"2026-09-10","2026-09-11");
        assertThatThrownBy(()->GitHistoryJobProtocol.requireBinding(changed,sha,frozen)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->GitHistoryJobProtocol.requireBinding(input,"0".repeat(64),frozen)).isInstanceOf(IOException.class);
    }
    @Test void damagedVersionTrailingDataOversizedFieldsAndPartialRecordsCannotBecomeEvidence()throws Exception {
        var input=input();Path file=temporary.resolve("evidence");GitHistoryEvidenceCodec.publish(file,value(input,"api.java","patch"));
        byte[] original=Files.readAllBytes(file);
        Files.write(file,new byte[]{1},StandardOpenOption.APPEND);assertThatThrownBy(()->GitHistoryEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        Files.write(file,Arrays.copyOf(original,original.length-1));assertThatThrownBy(()->GitHistoryEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        byte[] invalid=original.clone();invalid[3]=2;Files.write(file,invalid);assertThatThrownBy(()->GitHistoryEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->GitHistoryEvidenceCodec.publish(temporary.resolve("oversized"),value(input,"api.java","x".repeat(4_000_001)))).isInstanceOf(IOException.class).hasMessageContaining("bound");
        assertThat(temporary.resolve("oversized")).doesNotExist();
    }
    private GitHistoryJobProtocol.Input input()throws IOException {
        return new GitHistoryJobProtocol.Input(new GitSnapshotJobProtocol.Input(UUID.randomUUID().toString(),temporary.toRealPath().toString(),"refs/heads/main",null),"2026-09-11","2026-09-11");
    }
    private GitHistoryJobProtocol.Frozen value(GitHistoryJobProtocol.Input input,String path,String patch)throws IOException {
        String commit="a".repeat(40),sha=DurableCommandProtocol.hash(GitHistoryJobProtocol.input(input)),root=temporary.toRealPath().toString();
        var binding=new GitSnapshotJobProtocol.Binding(sha,root,root,temporary.resolve(".git").toString(),"",commit);
        var identity=new CommitIdentity("原作者","old@example.invalid","作者","new@example.invalid","2026-09-11T01:00:00Z");
        var contributor=new Contributor("identity","作者","new@example.invalid",false);
        var changes=List.of(new Change("evidence",path,null,"b".repeat(40),1,1,false,2,null,patch),
                new Change("secret",".env",null,"c".repeat(40),1,0,false,0,"SENSITIVE_CONTENT_WITHHELD",""));
        var commits=List.of(new Commit(commit,List.of(),"2026-09-11T01:00:00Z","提交\n正文",List.of(contributor),"ANALYZE",changes,identity,null,
                List.of(new CommitIdentity("共同作者","co@example.invalid","共同作者","co@example.invalid",null))));
        return new GitHistoryJobProtocol.Frozen(binding,new TemplateGitEvidence(TemplateGitEvidence.VERSION,input.source().selection().id(),commit,input.startDate(),input.endDate(),"Asia/Shanghai",null,commits));
    }
}
