package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.runtime.DurableCommandProtocol;
import io.opencode.loopper.template.SnapshotReview;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitReviewEvidenceCodecTest {
    @TempDir Path directory;
    @Test void streamingEvidenceBeyondSmallRecordLimitPreservesEveryUnitAndReference()throws Exception {
        directory=directory.toRealPath();var units=new ArrayList<SnapshotReview.Unit>();
        String content="原始代码行\n".repeat(2000);
        for(int i=0;i<320;i++)units.add(new SnapshotReview.Unit("unit-"+i,"Code.java","Code.java","FULL",content,null,
                List.of(new SnapshotReview.Reference("a".repeat(40),"Code.java","b".repeat(40),1,2000,content.stripTrailing()))));
        var value=value(units);Path file=directory.resolve("evidence");
        String sha=GitReviewEvidenceCodec.publish(file,value);
        assertThat(Files.size(file)).isGreaterThan(16*1024*1024);
        assertThat(GitReviewEvidenceCodec.read(file)).isEqualTo(value);
        assertThat(GitReviewEvidenceCodec.publish(file,value)).isEqualTo(sha);
        assertThat(GitReviewEvidenceCodec.hash(file)).isEqualTo(sha);
        assertThatThrownBy(()->GitReviewEvidenceCodec.publish(file,value(List.of()))).isInstanceOf(IOException.class);
        assertThat(GitReviewEvidenceCodec.hash(file)).isEqualTo(sha);
    }
    @Test void truncatedTrailingOversizedAndAliasedEvidenceAreRejectedWithoutReplacingExistingFiles()throws Exception {
        directory=directory.toRealPath();Path file=directory.resolve("evidence");GitReviewEvidenceCodec.publish(file,value(List.of()));
        byte[] original=Files.readAllBytes(file);Files.write(file,Arrays.copyOf(original,original.length-1));
        assertThatThrownBy(()->GitReviewEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        Files.write(file,Arrays.copyOf(original,original.length+1));
        assertThatThrownBy(()->GitReviewEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        byte[] invalidUtf8=original.clone();
        try(var in=new DataInputStream(new ByteArrayInputStream(original))) {
            in.readInt();int selectionBytes=in.readInt();invalidUtf8[8+selectionBytes+4]=(byte)0x80;
        }
        Files.write(file,invalidUtf8);assertThatThrownBy(()->GitReviewEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        var malformed=new ByteArrayOutputStream();try(var out=new DataOutputStream(malformed)){out.writeInt(1);out.writeInt(Integer.MAX_VALUE);}
        Files.write(file,malformed.toByteArray());assertThatThrownBy(()->GitReviewEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        Path outside=directory.resolve("original");Files.write(outside,original);Files.delete(file);Files.createSymbolicLink(file,outside);
        assertThatThrownBy(()->GitReviewEvidenceCodec.read(file)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->GitReviewEvidenceCodec.publish(file,value(List.of()))).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(outside)).containsExactly(original);
    }
    @Test void modeDatesScopeAndSelectionHaveIndependentExactBindings()throws Exception {
        directory=directory.toRealPath();var input=input();byte[] encoded=GitReviewJobProtocol.input(input);
        assertThat(GitReviewJobProtocol.input(encoded)).isEqualTo(input);
        assertThatThrownBy(()->GitReviewJobProtocol.input(Arrays.copyOf(encoded,encoded.length+1))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new GitReviewJobProtocol.Input(input.source(),input.projectId(),SnapshotReview.Mode.FULL,"2026-09-11",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GitReviewJobProtocol.Input(input.source(),input.projectId(),SnapshotReview.Mode.DATE_INCREMENTAL,null,null)).isInstanceOf(IllegalArgumentException.class);
        var value=value(List.of());GitReviewJobProtocol.requireBinding(input,DurableCommandProtocol.hash(encoded),value);
        var other=new GitReviewJobProtocol.Input(input.source(),"another-project",input.mode(),null,null);
        assertThatThrownBy(()->GitReviewJobProtocol.requireBinding(other,value.selection().binding().inputSha256(),value)).isInstanceOf(IOException.class);
        var dated=new GitReviewJobProtocol.Input(input.source(),input.projectId(),SnapshotReview.Mode.DATE_INCREMENTAL,"2026-09-11","2026-09-11");
        assertThatThrownBy(()->GitReviewJobProtocol.requireBinding(dated,value.selection().binding().inputSha256(),value)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->GitReviewJobProtocol.requireBinding(input,"0".repeat(64),value)).isInstanceOf(IOException.class);
    }
    private GitReviewJobProtocol.Input input() {
        return new GitReviewJobProtocol.Input(new GitSnapshotJobProtocol.Input("10000000-0000-0000-0000-000000000001",directory.toString(),"refs/heads/main",null),
                "project",SnapshotReview.Mode.FULL,null,null);
    }
    private GitReviewJobProtocol.Frozen value(List<SnapshotReview.Unit> units)throws IOException {
        var input=input();String commit="a".repeat(40),tree="c".repeat(40),time="2026-09-29T00:00:00Z";
        var binding=new GitSnapshotJobProtocol.Binding(DurableCommandProtocol.hash(GitReviewJobProtocol.input(input)),directory.toString(),directory.toString(),directory.resolve(".git").toString(),"",commit);
        return new GitReviewJobProtocol.Frozen(new GitReviewJobProtocol.Selection(binding,time),new SnapshotReview.Snapshot(commit,null,commit,null,tree,time,
                null,null,"FROZEN_BRANCH_TIP",false,false,List.of(new SnapshotReview.File(commit,"Code.java","b".repeat(40),"100644",50000,null)),List.copyOf(units),input.scope(binding)));
    }
}
