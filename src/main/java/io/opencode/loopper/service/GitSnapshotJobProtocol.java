package io.opencode.loopper.service;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.opencode.loopper.runtime.DurableCommandProtocol.check;

/** Dependency-free immutable capture records; process grants and stop proofs belong to DurableCommands. */
public final class GitSnapshotJobProtocol {
    private static final int LIMIT=16*1024*1024;
    private GitSnapshotJobProtocol() { }
    public record Input(String nodeId,String projectPath,String ref,String remote) {
        public Input {
            UUID.fromString(nodeId);
            if(!Path.of(projectPath).isAbsolute()||projectPath.length()>4096||ref==null||!ref.startsWith("refs/heads/")
                    ||ref.length()>2048||ref.chars().anyMatch(Character::isISOControl)
                    ||remote!=null&&(remote.isBlank()||remote.length()>512||remote.startsWith("-")||remote.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid snapshot input");
        }
        public GitCommitReader.Selection selection(){return new GitCommitReader.Selection(ref,remote);}
    }
    public record Binding(String inputSha256,String project,String repository,String gitDirectory,String prefix,String commit) {
        public Binding {
            if(!inputSha256.matches("[0-9a-f]{64}")||!Path.of(project).isAbsolute()||!Path.of(repository).isAbsolute()
                    ||!Path.of(gitDirectory).isAbsolute()||!prefix.isEmpty()&&(!prefix.endsWith("/")||prefix.startsWith("/")
                    ||Arrays.asList(prefix.split("/")).contains("..")))throw new IllegalArgumentException("Invalid snapshot binding");
            GitSnapshotInventory.objectId(commit);
        }
        public static Binding from(String sha,GitCommitReader.Source source,String commit) {
            return new Binding(sha,source.project().toString(),source.repository().toString(),source.gitDirectory().toString(),source.prefix(),commit);
        }
        public GitCommitReader.Source source(Input input) {
            return new GitCommitReader.Source(Path.of(project),Path.of(repository),Path.of(gitDirectory),prefix,"",input.selection());
        }
        public boolean matches(GitCommitReader.Source source) {
            return project.equals(source.project().toString())&&repository.equals(source.repository().toString())
                    &&gitDirectory.equals(source.gitDirectory().toString())&&prefix.equals(source.prefix());
        }
    }
    public record Snapshot(Binding binding,GitCommitReader.Snapshot snapshot) { }
    public static byte[] input(Input input)throws IOException {
        return encode(out->{string(out,input.nodeId());string(out,input.projectPath());string(out,input.ref());string(out,input.remote()==null?"":input.remote());});
    }
    public static Input input(byte[] bytes)throws IOException {
        try(var in=decode(bytes)){String id=string(in),project=string(in),ref=string(in),remote=string(in);end(in);return new Input(id,project,ref,remote.isEmpty()?null:remote);}
    }
    public static byte[] binding(Binding binding)throws IOException {return encode(out->binding(out,binding));}
    public static Binding binding(byte[] bytes)throws IOException {try(var in=decode(bytes)){var binding=binding(in);end(in);return binding;}}
    public static byte[] snapshot(Snapshot value)throws IOException {
        return encode(out->{binding(out,value.binding());var snapshot=value.snapshot();string(out,snapshot.commitSha());string(out,snapshot.treeSha());
            string(out,snapshot.projectPrefix());out.writeInt(snapshot.files().size());
            for(var file:snapshot.files()){string(out,file.path());string(out,file.blobSha());string(out,file.mode());out.writeLong(file.sizeBytes());string(out,file.limitation()==null?"":file.limitation());}});
    }
    public static Snapshot snapshot(byte[] bytes)throws IOException {
        try(var in=decode(bytes)) {
            var binding=binding(in);String commit=string(in),tree=GitSnapshotInventory.objectId(string(in)),prefix=string(in);
            if(!commit.equals(binding.commit())||!prefix.equals(binding.prefix()))throw new IOException("Snapshot binding differs");
            int count=count(in,50000);var files=new ArrayList<GitSnapshotInventory.Entry>();var listing=new StringBuilder();
            for(int i=0;i<count;i++) {
                String path=string(in),blob=string(in),mode=string(in);long size=in.readLong();String limitation=string(in);
                files.add(new GitSnapshotInventory.Entry(path,blob,mode,size,limitation.isEmpty()?null:limitation));
                boolean submodule=mode.equals("160000");listing.append(mode).append(submodule?" commit ":" blob ").append(blob).append(' ')
                        .append(submodule?"-":Long.toString(size)).append('\t').append(path).append('\0');
            }
            end(in);if(!GitSnapshotInventory.parse(listing.toString()).equals(files))throw new IOException("Snapshot inventory differs");
            return new Snapshot(binding,new GitCommitReader.Snapshot(commit,tree,prefix,List.copyOf(files)));
        }
    }
    public static byte[] read(Path file)throws IOException {
        check(file);try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(LIMIT+1);if(bytes.length>LIMIT)throw new IOException("Snapshot evidence limit");return bytes;}
    }
    public static void publish(Path file,byte[] bytes)throws IOException {
        check(file);if(bytes.length>LIMIT)throw new IOException("Snapshot evidence limit");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){if(!Arrays.equals(read(file),bytes))throw new IOException("Snapshot evidence differs");return;}
        Path temporary=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".part");
        Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        check(file);
        try{Files.createLink(file,temporary);}catch(FileAlreadyExistsException raced){if(!Arrays.equals(read(file),bytes))throw raced;}
        finally{Files.deleteIfExists(temporary);}
    }
    private interface Encoder {void write(DataOutputStream out)throws IOException;}
    private static byte[] encode(Encoder encoder)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);encoder.write(out);}
        if(bytes.size()>LIMIT)throw new IOException("Snapshot evidence limit");return bytes.toByteArray();
    }
    private static DataInputStream decode(byte[] bytes)throws IOException {if(bytes.length>LIMIT)throw new IOException("Snapshot evidence limit");var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IOException("Snapshot version");return in;}
    private static void binding(DataOutputStream out,Binding value)throws IOException {for(String field:List.of(value.inputSha256(),value.project(),value.repository(),value.gitDirectory(),value.prefix(),value.commit()))string(out,field);}
    private static Binding binding(DataInputStream in)throws IOException {return new Binding(string(in),string(in),string(in),string(in),string(in),string(in));}
    private static void string(DataOutputStream out,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IOException("Snapshot field limit");out.writeInt(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in)throws IOException {int size=count(in,16384);byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);}
    private static int count(DataInputStream in,int max)throws IOException {int count=in.readInt();if(count<0||count>max)throw new IOException("Snapshot length");return count;}
    private static void end(DataInputStream in)throws IOException {if(in.available()!=0)throw new IOException("Unexpected snapshot fields");}
}
