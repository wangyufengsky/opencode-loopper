package io.opencode.loopper.runtime;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Small JDK-only wire format shared by the application and its detached command supervisor. */
public final class DurableCommandProtocol {
    public static final int OUTPUT_LIMIT = 64 * 1024;
    private static final int VERSION = 1, FILE_LIMIT = 512 * 1024;
    private DurableCommandProtocol() { }

    public record Identity(long pid, String startedAt) {
        public Identity {
            if (pid <= 0 || startedAt == null) throw new IllegalArgumentException("Invalid process identity");
            Instant.parse(startedAt);
        }
        public static Identity current() {
            var handle = ProcessHandle.current();
            return new Identity(handle.pid(), handle.info().startInstant().orElseThrow().toString());
        }
        public boolean alive() {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .filter(handle -> handle.info().startInstant().map(value -> value.toString().equals(startedAt)).orElse(true)).isPresent();
        }
        public boolean matches(ProcessHandle handle) {
            return handle.pid() == pid && handle.info().startInstant().map(value -> value.toString().equals(startedAt)).orElse(false);
        }
    }
    public record Preparation(String name,String directory,List<String> argv) {
        public Preparation {argv=List.copyOf(argv);if(name==null||!name.matches("[A-Z][A-Z0-9_]{0,39}"))throw new IllegalArgumentException("Invalid preparation name");validate(directory,argv);}
    }
    public record Request(String id, String directory, List<String> argv, int timeoutSeconds,List<Preparation> preparations) {
        public Request(String id,String directory,List<String> argv,int timeoutSeconds){this(id,directory,argv,timeoutSeconds,List.of());}
        public Request {
            UUID.fromString(id); argv = List.copyOf(argv);preparations=preparations==null?List.of():List.copyOf(preparations);validate(directory,argv);
            if (timeoutSeconds < 1 || timeoutSeconds > 3600 || preparations.size()>4
                    || preparations.stream().anyMatch(p->!Path.of(p.directory()).normalize().startsWith(Path.of(directory).normalize())))
                throw new IllegalArgumentException("Invalid bounded command request");
        }
    }
    public record Registration(String requestSha256, Identity worker) { }
    public record Result(String requestSha256, Identity worker, Integer exitCode, boolean launched,
                         boolean timedOut, boolean cancelled, boolean outputTruncated, boolean stopConfirmed,
                         String output, String error, List<Identity> children,List<Result> preparations) {
        public Result(String requestSha256,Identity worker,Integer exitCode,boolean launched,boolean timedOut,boolean cancelled,boolean outputTruncated,boolean stopConfirmed,String output,String error,List<Identity> children){this(requestSha256,worker,exitCode,launched,timedOut,cancelled,outputTruncated,stopConfirmed,output,error,children,List.of());}
        public Result {
            children = List.copyOf(children);preparations=preparations==null?List.of():List.copyOf(preparations);
            if(preparations.size()>4||preparations.stream().anyMatch(p->!p.preparations().isEmpty()||!p.requestSha256().equals(requestSha256)||!p.worker().equals(worker)))throw new IllegalArgumentException("Invalid preparation receipts");
        }
        public boolean successful(){return launched&&stopConfirmed&&!timedOut&&!cancelled&&!outputTruncated&&error.isEmpty()&&Objects.equals(exitCode,0);}
    }

    public static byte[] request(Request value) throws IOException {
        return encode(value.preparations().isEmpty()?1:2,out -> { string(out, value.id()); string(out, value.directory()); argv(out,value.argv());out.writeInt(value.timeoutSeconds());
            if(!value.preparations().isEmpty()){out.writeInt(value.preparations().size());for(var step:value.preparations()){string(out,step.name());string(out,step.directory());argv(out,step.argv());}} });
    }
    public static Request request(byte[] bytes) throws IOException {
        try (var in = raw(bytes)) {
            int version=version(in,2);String id = string(in), directory = string(in);var argv=argv(in);int timeout=in.readInt();var steps=new ArrayList<Preparation>();
            if(version==2){int count=count(in,4);if(count==0)throw new IOException("Empty versioned preparation");for(int i=0;i<count;i++)steps.add(new Preparation(string(in),string(in),argv(in)));}
            var result = new Request(id, directory, argv,timeout,steps); end(in); return result;
        }
    }
    public static byte[] registration(Registration value) throws IOException {
        return encode(out -> { string(out, value.requestSha256()); identity(out, value.worker()); });
    }
    public static Registration registration(byte[] bytes) throws IOException {
        try (var in = input(bytes)) { var result = new Registration(string(in), identity(in)); end(in); return result; }
    }
    public static byte[] result(Result value) throws IOException {
        return encode(value.preparations().isEmpty()?1:2,out -> { string(out, value.requestSha256()); identity(out, value.worker());
            out.writeBoolean(value.exitCode() != null); if (value.exitCode() != null) out.writeInt(value.exitCode());
            out.writeBoolean(value.launched()); out.writeBoolean(value.timedOut()); out.writeBoolean(value.cancelled());
            out.writeBoolean(value.outputTruncated()); out.writeBoolean(value.stopConfirmed());
            string(out, value.output()); string(out, value.error()); out.writeInt(value.children().size());
            for (var child : value.children()) identity(out, child);
            if(!value.preparations().isEmpty()){out.writeInt(value.preparations().size());for(var step:value.preparations()){byte[] bytes=result(step);out.writeInt(bytes.length);out.write(bytes);}} });
    }
    public static Result result(byte[] bytes) throws IOException {
        return result(bytes,2);
    }
    private static Result result(byte[] bytes,int maximumVersion) throws IOException {
        try (var in = raw(bytes)) {
            int version=version(in,maximumVersion);
            var hash = string(in); var worker = identity(in); Integer exit = in.readBoolean() ? in.readInt() : null;
            boolean launched = in.readBoolean(), timeout = in.readBoolean(), cancelled = in.readBoolean(), truncated = in.readBoolean(), stopped = in.readBoolean();
            String output = string(in), error = string(in); int count = count(in, 1024); var children = new ArrayList<Identity>();
            for (int i = 0; i < count; i++) children.add(identity(in));var steps=new ArrayList<Result>();
            if(version==2){int size=count(in,4);if(size==0)throw new IOException("Empty preparation results");for(int i=0;i<size;i++){int length=count(in,FILE_LIMIT);byte[] step=in.readNBytes(length);if(step.length!=length)throw new EOFException();steps.add(result(step,1));}}
            end(in);return new Result(hash, worker, exit, launched, timeout, cancelled, truncated, stopped, output, error, children,steps);
        }
    }
    public static boolean matches(Request request,Result result) {
        var steps=result.preparations();if(steps.size()>request.preparations().size())return false;
        for(int i=0;i<steps.size()-1;i++)if(!steps.get(i).successful())return false;
        return !result.launched()||steps.size()==request.preparations().size()&&steps.stream().allMatch(Result::successful);
    }
    private static void validate(String directory,List<String> argv) {
        if(directory==null||!Path.of(directory).isAbsolute()||argv.isEmpty()||argv.size()>128
                ||argv.stream().anyMatch(v->v==null||v.isBlank()||v.length()>4096||v.indexOf('\0')>=0))
            throw new IllegalArgumentException("Invalid direct command");
    }
    private static void argv(DataOutputStream out,List<String> argv)throws IOException {out.writeInt(argv.size());for(String arg:argv)string(out,arg);}
    private static List<String> argv(DataInputStream in)throws IOException {int size=count(in,128);var args=new ArrayList<String>();for(int i=0;i<size;i++)args.add(string(in));return args;}
    public static byte[] read(Path file) throws IOException {
        check(file);
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(FILE_LIMIT + 1); if (bytes.length > FILE_LIMIT) throw new IOException("Command evidence is too large"); return bytes;
        }
    }
    /** The caller owns a job lock; published evidence never overwrites an existing value. */
    public static void publish(Path file, byte[] bytes) throws IOException {
        check(file); if (bytes.length > FILE_LIMIT) throw new IOException("Command evidence is too large");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(read(file), bytes)) throw new IOException("Command evidence differs"); return;
        }
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".part");
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        check(file);
        // createLink gives publish-if-absent semantics; a crash may leave the harmless temporary link.
        try { Files.createLink(file, temporary); }
        catch (FileAlreadyExistsException raced) { if (!Arrays.equals(read(file), bytes)) throw raced; }
        finally { Files.deleteIfExists(temporary); }
    }
    public static void check(Path path) throws IOException {
        for (Path item = path.toAbsolutePath(); item != null; item = item.getParent())
            if (Files.isSymbolicLink(item)) throw new IOException("Command evidence path contains a symbolic link");
    }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private interface Encoder { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(Encoder encoder) throws IOException {
        return encode(VERSION,encoder);
    }
    private static byte[] encode(int version,Encoder encoder) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { out.writeInt(version); encoder.write(out); } return bytes.toByteArray();
    }
    private static DataInputStream input(byte[] bytes) throws IOException {
        var in=raw(bytes);version(in,1);return in;
    }
    private static DataInputStream raw(byte[] bytes)throws IOException {if(bytes.length>FILE_LIMIT)throw new IOException("Command evidence is too large");return new DataInputStream(new ByteArrayInputStream(bytes));}
    private static int version(DataInputStream in,int maximum)throws IOException {int version=in.readInt();if(version<1||version>maximum)throw new IOException("Unsupported command evidence");return version;}
    private static void string(DataOutputStream out, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int size = count(in, FILE_LIMIT); byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException(); return new String(bytes, StandardCharsets.UTF_8);
    }
    private static int count(DataInputStream in, int max) throws IOException { int value = in.readInt(); if (value < 0 || value > max) throw new IOException("Invalid command evidence length"); return value; }
    private static void identity(DataOutputStream out, Identity value) throws IOException { out.writeLong(value.pid()); string(out, value.startedAt()); }
    private static Identity identity(DataInputStream in) throws IOException { return new Identity(in.readLong(), string(in)); }
    private static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Unexpected command evidence fields"); }
}
