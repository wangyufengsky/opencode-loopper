package io.opencode.loopper.service;

import static io.opencode.loopper.runtime.DurableCommandProtocol.check;
import io.opencode.loopper.template.TemplateGitEvidence;
import io.opencode.loopper.template.TemplateGitEvidence.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Streaming immutable evidence transport for the helper; never embeds Spring or a JSON library. */
public final class GitHistoryEvidenceCodec {
    static final long MAX_BYTES=320_000_000;
    private static final int FIELD_BYTES=4_000_000,MAX_ITEMS=100_000;
    private GitHistoryEvidenceCodec() { }

    public static String publish(Path file,GitHistoryJobProtocol.Frozen value)throws IOException {
        check(file);Path temporary=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".part");
        try {
            try(var out=new DataOutputStream(new BufferedOutputStream(new BoundedOutput(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS))))) {write(out,value);}
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
            String sha=hash(temporary);check(file);
            try{Files.createLink(file,temporary);}
            catch(FileAlreadyExistsException exists){if(!hash(file).equals(sha))throw new IOException("History evidence differs");}
            return sha;
        }finally{Files.deleteIfExists(temporary);}
    }
    public static GitHistoryJobProtocol.Frozen read(Path file)throws IOException {
        check(file);if(Files.size(file)>MAX_BYTES)throw new LimitException("History evidence bound");
        try(var in=new DataInputStream(new BufferedInputStream(new BoundedInput(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))))) {
            if(in.readInt()!=1)throw new IOException("History evidence version");
            int length=count(in,32768);byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();
            var binding=GitSnapshotJobProtocol.binding(bytes);
            String version=string(in),branch=string(in),head=string(in),start=string(in),end=string(in),zone=string(in),mailmap=string(in);
            int size=count(in,MAX_ITEMS);var commits=new ArrayList<Commit>();long patchCharacters=0;
            for(int i=0;i<size;i++) {
                var commit=commit(in);commits.add(commit);
                patchCharacters+=commit.changes().stream().mapToLong(c->c.patch().length()).sum();
                if(patchCharacters>64_000_000)throw new LimitException("History patches bound");
            }
            if(in.read()!=-1)throw new IOException("History evidence extra fields");
            return new GitHistoryJobProtocol.Frozen(binding,new TemplateGitEvidence(version,branch,head,start,end,zone,mailmap,commits));
        }
    }
    public static String hash(Path file)throws IOException {
        check(file);var digest=digest();long count=0;
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[65536];int length;
            while((length=in.read(buffer))!=-1){count+=length;if(count>MAX_BYTES)throw new LimitException("History evidence bound");digest.update(buffer,0,length);}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void write(DataOutputStream out,GitHistoryJobProtocol.Frozen value)throws IOException {
        out.writeInt(1);byte[] binding=GitSnapshotJobProtocol.binding(value.binding());out.writeInt(binding.length);out.write(binding);
        var evidence=value.evidence();for(String field:Arrays.asList(evidence.version(),evidence.branchId(),evidence.head(),evidence.startDate(),evidence.endDate(),evidence.timezone(),evidence.mailmapHash()))string(out,field);
        count(out,evidence.commits().size());long patches=0;
        for(var commit:evidence.commits()) {
            patches+=commit.changes().stream().mapToLong(c->c.patch().length()).sum();
            if(patches>64_000_000)throw new LimitException("History patches bound");
            string(out,commit.sha());strings(out,commit.parents());string(out,commit.committedAt());string(out,commit.message());
            count(out,commit.contributors().size());
            for(var person:commit.contributors()){string(out,person.identity());string(out,person.name());string(out,person.email());out.writeBoolean(person.robot());}
            string(out,commit.disposition());count(out,commit.changes().size());for(var change:commit.changes())change(out,change);
            identity(out,commit.author());identity(out,commit.committer());count(out,commit.coauthors().size());for(var person:commit.coauthors())identity(out,person);
        }
    }
    private static Commit commit(DataInputStream in)throws IOException {
        String sha=string(in);var parents=strings(in);String time=string(in),message=string(in);
        int size=count(in,MAX_ITEMS);var contributors=new ArrayList<Contributor>();
        for(int i=0;i<size;i++)contributors.add(new Contributor(string(in),string(in),string(in),in.readBoolean()));
        String disposition=string(in);size=count(in,MAX_ITEMS);var changes=new ArrayList<Change>();
        for(int i=0;i<size;i++)changes.add(change(in));
        var author=identity(in);var committer=identity(in);size=count(in,MAX_ITEMS);var coauthors=new ArrayList<CommitIdentity>();
        for(int i=0;i<size;i++)coauthors.add(identity(in));
        return new Commit(sha,parents,time,message,contributors,disposition,changes,author,committer,coauthors);
    }
    private static void change(DataOutputStream out,Change value)throws IOException {
        string(out,value.evidenceId());string(out,value.path());string(out,value.beforeBlob());string(out,value.afterBlob());
        out.writeLong(value.additions());out.writeLong(value.deletions());out.writeBoolean(value.binary());out.writeLong(value.effectiveLines());
        string(out,value.exclusionReason());string(out,value.patch());
    }
    private static Change change(DataInputStream in)throws IOException {
        return new Change(string(in),string(in),string(in),string(in),in.readLong(),in.readLong(),in.readBoolean(),in.readLong(),string(in),string(in));
    }
    private static void identity(DataOutputStream out,CommitIdentity value)throws IOException {
        out.writeBoolean(value!=null);if(value!=null)for(String field:Arrays.asList(value.rawName(),value.rawEmail(),value.name(),value.email(),value.time()))string(out,field);
    }
    private static CommitIdentity identity(DataInputStream in)throws IOException {
        return in.readBoolean()?new CommitIdentity(string(in),string(in),string(in),string(in),string(in)):null;
    }
    private static void strings(DataOutputStream out,List<String> values)throws IOException {count(out,values.size());for(String value:values)string(out,value);}
    private static List<String> strings(DataInputStream in)throws IOException {int size=count(in,MAX_ITEMS);var values=new ArrayList<String>();for(int i=0;i<size;i++)values.add(string(in));return values;}
    private static void string(DataOutputStream out,String value)throws IOException {
        if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>FIELD_BYTES)throw new LimitException("History field bound");out.writeInt(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream in)throws IOException {
        int length=in.readInt();if(length==-1)return null;if(length<0||length>FIELD_BYTES)throw new LimitException("History field bound");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);
    }
    private static void count(DataOutputStream out,int count)throws IOException {if(count<0||count>MAX_ITEMS)throw new LimitException("History item bound");out.writeInt(count);}
    private static int count(DataInputStream in,int max)throws IOException {int count=in.readInt();if(count<0||count>max)throw new LimitException("History item bound");return count;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static final class LimitException extends IOException {
        LimitException(String message){super(message);}
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private long count;
        BoundedOutput(OutputStream out){super(out);}
        @Override public void write(int value)throws IOException {reserve(1);out.write(value);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {reserve(length);out.write(bytes,offset,length);}
        private void reserve(int length)throws IOException {count+=length;if(count>MAX_BYTES)throw new LimitException("History evidence bound");}
    }
    private static final class BoundedInput extends FilterInputStream {
        private long count;
        BoundedInput(InputStream in){super(in);}
        @Override public int read()throws IOException {int value=in.read();if(value!=-1)used(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {int size=in.read(bytes,offset,length);if(size>0)used(size);return size;}
        private void used(int length)throws IOException {count+=length;if(count>MAX_BYTES)throw new LimitException("History evidence bound");}
    }
}
