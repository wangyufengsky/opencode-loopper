package io.opencode.loopper.service;

import static io.opencode.loopper.runtime.DurableCommandProtocol.check;
import io.opencode.loopper.template.SnapshotReview;
import io.opencode.loopper.template.SnapshotReview.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Streaming immutable evidence transport for the helper; never embeds Spring or a JSON library. */
public final class GitReviewEvidenceCodec {
    static final long MAX_BYTES=320_000_000;
    private static final int FIELD_BYTES=4_000_000,MAX_ITEMS=100_000;
    private GitReviewEvidenceCodec() { }

    public static String publish(Path file,GitReviewJobProtocol.Frozen value)throws IOException {
        check(file);Path temporary=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            try(var out=new DataOutputStream(new BufferedOutputStream(new BoundedOutput(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS))))) {write(out,value);}
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            String sha=hash(temporary);check(file);
            try{Files.createLink(file,temporary);}
            catch(FileAlreadyExistsException exists){if(!hash(file).equals(sha))throw new IOException("Review evidence differs");}
            return sha;
        }finally{Files.deleteIfExists(temporary);}
    }
    public static GitReviewJobProtocol.Frozen read(Path file)throws IOException {
        check(file);if(Files.size(file)>MAX_BYTES)throw new LimitException("Review evidence bound");
        try(var in=new DataInputStream(new BufferedInputStream(new BoundedInput(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))))) {
            if(in.readInt()!=1)throw new IOException("Review evidence version");
            int length=count(in,32768);byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();
            var selection=GitReviewJobProtocol.selection(bytes);
            String source=string(in),baseline=string(in),target=string(in),beforeTree=string(in),afterTree=string(in);
            String captured=string(in),start=string(in),end=string(in),basis=string(in);boolean anomaly=in.readBoolean(),same=in.readBoolean();
            String scope=string(in);int size=count(in,MAX_ITEMS);var files=new ArrayList<SnapshotReview.File>();
            for(int i=0;i<size;i++)files.add(new SnapshotReview.File(string(in),string(in),string(in),string(in),in.readLong(),string(in)));
            size=count(in,MAX_ITEMS);var units=new ArrayList<Unit>();
            for(int i=0;i<size;i++) {
                units.add(unit(in));
            }
            if(in.read()!=-1)throw new IOException("Review evidence extra fields");
            return new GitReviewJobProtocol.Frozen(selection,new SnapshotReview.Snapshot(source,baseline,target,beforeTree,afterTree,captured,
                    start,end,basis,anomaly,same,List.copyOf(files),List.copyOf(units),scope));
        }
    }
    private static void write(DataOutputStream out,GitReviewJobProtocol.Frozen value)throws IOException {
        out.writeInt(1);byte[] selection=GitReviewJobProtocol.selection(value.selection());out.writeInt(selection.length);out.write(selection);
        var snapshot=value.snapshot();
        for(String field:Arrays.asList(snapshot.sourceSha(),snapshot.baselineSha(),snapshot.targetSha(),snapshot.baselineTree(),snapshot.targetTree(),
                snapshot.capturedAt(),snapshot.startInclusive(),snapshot.endExclusive(),snapshot.selectionBasis()))string(out,field);
        out.writeBoolean(snapshot.nonMonotonic());out.writeBoolean(snapshot.noChanges());string(out,snapshot.scopeIdentity());
        count(out,snapshot.files().size());
        for(var file:snapshot.files()) {
            string(out,file.version());string(out,file.path());string(out,file.blob());string(out,file.mode());out.writeLong(file.bytes());string(out,file.limitation());
        }
        count(out,snapshot.units().size());
        for(var unit:snapshot.units()) {
            for(String field:Arrays.asList(unit.id(),unit.path(),unit.beforePath(),unit.change(),unit.excerpt(),unit.limitation()))string(out,field);
            count(out,unit.initialEvidence().size());
            for(var ref:unit.initialEvidence()) {
                string(out,ref.version());string(out,ref.path());string(out,ref.blob());out.writeInt(ref.startLine());out.writeInt(ref.endLine());string(out,ref.quote());
            }
        }
    }
    private static Unit unit(DataInputStream in)throws IOException {
        String id=string(in),path=string(in),before=string(in),change=string(in),excerpt=string(in),limitation=string(in);
        int size=count(in,MAX_ITEMS);var references=new ArrayList<Reference>();
        for(int i=0;i<size;i++)references.add(new Reference(string(in),string(in),string(in),in.readInt(),in.readInt(),string(in)));
        return new Unit(id,path,before,change,excerpt,limitation,List.copyOf(references));
    }
    public static String hash(Path file)throws IOException {
        check(file);var digest=digest();long count=0;
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[65536];int length;
            while((length=in.read(buffer))!=-1){count+=length;if(count>MAX_BYTES)throw new LimitException("Review evidence bound");digest.update(buffer,0,length);}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void string(DataOutputStream out,String value)throws IOException {
        if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>FIELD_BYTES)throw new LimitException("Review field bound");out.writeInt(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream in)throws IOException {
        int length=in.readInt();if(length==-1)return null;if(length<0||length>FIELD_BYTES)throw new LimitException("Review field bound");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static void count(DataOutputStream out,int count)throws IOException {if(count<0||count>MAX_ITEMS)throw new LimitException("Review item bound");out.writeInt(count);}
    private static int count(DataInputStream in,int max)throws IOException {int count=in.readInt();if(count<0||count>max)throw new LimitException("Review item bound");return count;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static final class LimitException extends IOException {
        LimitException(String message){super(message);}
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private long count;
        BoundedOutput(OutputStream out){super(out);}
        @Override public void write(int value)throws IOException {reserve(1);out.write(value);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {reserve(length);out.write(bytes,offset,length);}
        private void reserve(int length)throws IOException {count+=length;if(count>MAX_BYTES)throw new LimitException("Review evidence bound");}
    }
    private static final class BoundedInput extends FilterInputStream {
        private long count;
        BoundedInput(InputStream in){super(in);}
        @Override public int read()throws IOException {int value=in.read();if(value!=-1)used(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {int size=in.read(bytes,offset,length);if(size>0)used(size);return size;}
        private void used(int length)throws IOException {count+=length;if(count>MAX_BYTES)throw new LimitException("Review evidence bound");}
    }
}
