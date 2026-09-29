package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

/** Exact URL and commit transport. The only write is creation of one previously absent result ref. */
@Component
public final class GitPublicationTransport {
    private static final Duration READ=Duration.ofSeconds(30),WRITE=Duration.ofSeconds(120);
    private final GitEvidenceProcess git;
    public GitPublicationTransport(GitEvidenceProcess git){this.git=git;}
    public List<String> remotes(Path repository) {
        var names=local(repository,List.of("remote")).lines().filter(v->!v.isBlank()).toList();
        if(names.size()>100)throw invalid("WORKFLOW_PUSH_REMOTE_LIMIT");for(var name:names)name(name);return names;
    }
    public String target(Path repository,String remote) {
        name(remote);var urls=local(repository,List.of("remote","get-url","--push","--all","--",remote)).lines().toList();
        if(urls.size()!=1)throw invalid("WORKFLOW_PUSH_REMOTE_AMBIGUOUS");String url=urls.getFirst();validateUrl(url);
        if(!local(repository,List.of("ls-remote","--get-url","--",url)).strip().equals(url))throw invalid("WORKFLOW_PUSH_TARGET_CHANGED");
        return url;
    }
    public void requireSource(GitPushProtocol.Input input) {
        var scope=GitProjectScope.require(git,Path.of(input.project()));
        if(!scope.repository().toString().equals(input.repository())||!gitDirectory(scope.repository()).equals(input.gitDirectory()))throw invalid("WORKFLOW_PUSH_SOURCE_CHANGED");
        if(!target(scope.repository(),input.remote()).equals(input.url()))throw invalid("WORKFLOW_PUSH_TARGET_CHANGED");
        String ref="refs/heads/"+input.branch();
        var symbolic=git.run(scope.repository(),READ,List.of("symbolic-ref","--quiet",ref));
        if(symbolic.exitCode()!=1||!local(scope.repository(),List.of("rev-parse","--verify",ref)).strip().equals(input.commit())
                ||!local(scope.repository(),List.of("rev-parse",input.commit()+"^{tree}")).strip().equals(input.tree()))throw invalid("WORKFLOW_PUSH_SOURCE_CHANGED");
    }
    /** null means an authoritative successful advertisement with this exact ref absent. */
    public String observe(GitPushProtocol.Input input) {
        requireSource(input);String ref="refs/heads/"+input.branch();
        var result=network(input,READ,List.of("ls-remote","--refs","--",input.url(),ref));
        if(result.exitCode()!=0)throw invalid(result.diagnostic().code());
        String value=null;
        for(var line:result.output().lines().toList()){
            if(line.isBlank())continue;String[] fields=line.split("\\t",-1);
            if(fields.length!=2||!fields[1].equals(ref)||!fields[0].matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")||value!=null)throw invalid("WORKFLOW_PUSH_OBSERVATION_INVALID");value=fields[0];
        }
        return value;
    }
    public void publish(GitPushProtocol.Input input) {
        String before=observe(input);if(input.commit().equals(before))return;
        if(before!=null)throw invalid("WORKFLOW_PUSH_REF_CONFLICT");
        requireSource(input);String ref="refs/heads/"+input.branch();
        // An explicit empty expected value is a create-only CAS, never permission to replace history.
        // No --force, '+' refspec, implicit tracking lease, tags, mirror, submodule or upstream mutation.
        var result=network(input,WRITE,List.of("push","--porcelain","--no-signed","--no-follow-tags","--recurse-submodules=no",
                "--force-with-lease="+ref+":","--",input.url(),input.commit()+":"+ref));
        // Always re-read the actual remote, including a nonzero acknowledgement that may follow an accepted write.
        String after=observe(input);
        if(input.commit().equals(after))return;
        if(after!=null)throw invalid("WORKFLOW_PUSH_REF_CONFLICT");
        throw invalid(result.exitCode()==0?"WORKFLOW_PUSH_RESULT_UNCONFIRMED":result.diagnostic().code());
    }
    private GitEvidenceProcess.Result network(GitPushProtocol.Input input,Duration timeout,List<String> command) {
        var args=new ArrayList<>(List.of("-c","http.followRedirects=false","-c","push.followTags=false","-c","push.recurseSubmodules=no","-c","submodule.recurse=false"));args.addAll(command);
        return git.remote(Path.of(input.repository()),Path.of(input.project()),timeout,args,input.url());
    }
    private String local(Path repository,List<String> args){var result=git.run(repository,READ,args);if(result.exitCode()!=0)throw invalid(result.diagnostic().code());return result.output();}
    public String gitDirectory(Path repository){try{return Path.of(local(repository,List.of("rev-parse","--absolute-git-dir")).strip()).toRealPath().toString();}catch(java.io.IOException failure){throw invalid("WORKFLOW_PUSH_SOURCE_CHANGED");}}
    public static void name(String value){if(value==null||!value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))throw invalid("WORKFLOW_PUSH_REMOTE_INVALID");}
    public static void validateUrl(String value) {
        if(value==null||value.isBlank()||value.length()>4096||value.chars().anyMatch(Character::isISOControl)||value.startsWith("-"))throw invalid("WORKFLOW_PUSH_URL_INVALID");
        if(value.startsWith("http://")||value.startsWith("https://")){GitHttpAuthentication.origin(value);return;}
        try {
            if(value.startsWith("ssh://")) {var uri=URI.create(value);if(uri.getHost()==null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getUserInfo()!=null&&uri.getUserInfo().contains(":"))throw invalid("WORKFLOW_PUSH_URL_INVALID");return;}
            if(value.startsWith("file://")){if(!Path.of(URI.create(value)).isAbsolute())throw invalid("WORKFLOW_PUSH_URL_INVALID");return;}
            try {if(Path.of(value).isAbsolute())return;}catch(java.nio.file.InvalidPathException ignored){/* SCP syntax is not a Windows filesystem path. */}
            if(value.matches("(?:[A-Za-z0-9_.-]+@)?[A-Za-z0-9_.-]+:[^\\s:?#]+"))return;
        }catch(RuntimeException invalid){throw invalid("WORKFLOW_PUSH_URL_INVALID");}
        throw invalid("WORKFLOW_PUSH_URL_INVALID");
    }
    private static TaskFailure invalid(String code){return new TaskFailure(code!=null&&code.matches("[A-Z][A-Z0-9_]{1,119}")?code:"WORKFLOW_PUSH_FAILED","远端推送未得到完整证明，请核对目标和原执行记录后重试。");}
}
