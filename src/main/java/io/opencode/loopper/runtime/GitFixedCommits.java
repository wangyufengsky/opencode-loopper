package io.opencode.loopper.runtime;

import io.opencode.loopper.service.ConflictException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed tree/parent commits and create-only refs. Never reads, stages or switches the checkout. */
@Component
public final class GitFixedCommits {
    private static final Duration TIMEOUT=Duration.ofSeconds(30);
    private final GitEvidenceProcess git;
    public GitFixedCommits(GitEvidenceProcess git){this.git=git;}

    public String create(Path repository, GitCommitIntent intent) {
        outsideTransaction();byte[] expected=content(intent);
        String format=read(repository,List.of("rev-parse","--show-object-format")).strip();
        if(!format.equals(intent.tree().length()==40?"sha1":"sha256"))throw invalid();
        String sha=hash(format,expected);
        var args=new ArrayList<>(List.of("-c","commit.gpgSign=false","-c","i18n.commitEncoding=UTF-8","commit-tree",intent.tree()));
        if(intent.parent()!=null){args.add("-p");args.add(intent.parent());}
        var env=new HashMap<>(environment());String date="@"+Instant.parse(intent.createdAt()).getEpochSecond()+" +0000";
        for(String role:List.of("AUTHOR","COMMITTER")) {
            env.put("GIT_"+role+"_NAME",intent.authorName());env.put("GIT_"+role+"_EMAIL",intent.authorEmail());env.put("GIT_"+role+"_DATE",date);
        }
        var result=git.input(repository,TIMEOUT,args,env,utf8(intent.message()));result.requireSuccess(args);
        if(!new String(result.output(),StandardCharsets.US_ASCII).strip().equals(sha))throw invalid();
        var check=List.of("cat-file","commit",sha);
        var stored=git.input(repository,TIMEOUT,check,environment(),new byte[0]);stored.requireSuccess(check);
        if(!Arrays.equals(stored.output(),expected))throw invalid();
        return sha;
    }

    /** Zero-old-value CAS never moves someone else's ref. An uncertain successful call replays unchanged. */
    public void reference(Path repository,String ref,String commit) {
        outsideTransaction();
        if(ref==null||!ref.startsWith("refs/")||!ref.matches("[a-zA-Z0-9/_-]{1,256}")||!objectId(commit))throw invalid();
        read(repository,List.of("check-ref-format",ref));
        if(!read(repository,List.of("cat-file","-t",commit)).strip().equals("commit"))throw invalid();
        String current=referenceValue(repository,ref);
        if(current!=null){if(!current.equals(commit))throw invalid();return;}
        var args=List.of("update-ref","--no-deref",ref,commit,"0".repeat(commit.length()));
        var result=git.createReference(repository,TIMEOUT,ref,commit,environment(),()->{
            if(referenceValue(repository,ref)!=null)throw invalid();
        });
        // Another recovery of this same intent may have won the identical CAS.
        if(!commit.equals(referenceValue(repository,ref))) { result.requireSuccess(args);throw invalid(); }
    }
    private String referenceValue(Path repository,String ref) {
        var symbolicArgs=List.of("symbolic-ref","--quiet",ref);
        var symbolic=git.run(repository,TIMEOUT,symbolicArgs,environment());
        if(symbolic.exitCode()==0)throw invalid();
        if(symbolic.exitCode()!=1)symbolic.requireSuccess(symbolicArgs);
        var args=List.of("rev-parse","--verify","--quiet",ref);
        var result=git.run(repository,TIMEOUT,args,environment());
        if(result.exitCode()==1)return null;
        result.requireSuccess(args);String value=result.output().strip();if(!objectId(value))throw invalid();return value;
    }
    private String read(Path repository,List<String> args) {
        var result=git.run(repository,TIMEOUT,args,environment());result.requireSuccess(args);return result.output();
    }
    static byte[] content(GitCommitIntent intent) {
        if(intent==null||!objectId(intent.tree())||intent.parent()!=null&&(!objectId(intent.parent())||intent.parent().length()!=intent.tree().length())
                ||!identity(intent.authorName())||!identity(intent.authorEmail())||intent.message()==null||intent.message().isBlank()
                ||intent.message().indexOf('\0')>=0||!intent.message().endsWith("\n"))throw invalid();
        long date;
        try{date=Instant.parse(intent.createdAt()).getEpochSecond();if(date<0)throw invalid();}
        catch(RuntimeException failure){throw invalid();}
        String identity=intent.authorName()+" <"+intent.authorEmail()+"> "+date+" +0000\n";
        byte[] body=utf8("tree "+intent.tree()+"\n"+(intent.parent()==null?"":"parent "+intent.parent()+"\n")
                +"author "+identity+"committer "+identity+"\n"+intent.message());
        if(body.length>24_000)throw invalid();return body;
    }
    private static boolean identity(String value) {
        return value!=null&&!value.isBlank()&&value.equals(value.strip())&&value.length()<=256
                &&value.codePoints().noneMatch(c->Character.isISOControl(c)||c=='<'||c=='>'||c==0x2028||c==0x2029);
    }
    private static boolean objectId(String value){return value!=null&&value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");}
    private static byte[] utf8(String value) {
        try{ByteBuffer buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;}
        catch(CharacterCodingException failure){throw invalid();}
    }
    private static String hash(String format,byte[] content) {
        try{var digest=MessageDigest.getInstance(format.equals("sha1")?"SHA-1":"SHA-256");digest.update(("commit "+content.length+"\0").getBytes(StandardCharsets.US_ASCII));return HexFormat.of().formatHex(digest.digest(content));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static Map<String,String> environment() {
        String nil=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"NUL":"/dev/null";
        return Map.of("GIT_CONFIG_GLOBAL",nil,"GIT_CONFIG_SYSTEM",nil);
    }
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Fixed Git commit I/O in transaction");}
    private static ConflictException invalid(){return new ConflictException("GIT_FIXED_COMMIT_CONFLICT","固定提交内容或目标引用不一致，现有文件与分支已保留。");}
}
