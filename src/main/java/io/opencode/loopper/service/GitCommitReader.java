package io.opencode.loopper.service;

import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.runtime.GitProjectScope;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Shared Git algorithm; usable inside the supervised helper without Spring or database dependencies. */
public final class GitCommitReader {
    private final GitEvidenceProcess git;
    public GitCommitReader(GitEvidenceProcess git){this.git=git;}
    /** Remote URLs are ephemeral and may contain credentials: never serialize Source into a run or log. */
    public record Selection(String ref,String remote) {
        public String id(){return (remote==null?"local:":"remote:"+remote+":")+ref;}
    }
    public record Source(Path project,Path repository,Path gitDirectory,String prefix,String remote,Selection branch) {
        @Override public String toString(){return "GitCommitSource[branch="+branch.id()+", prefix="+prefix+"]";}
    }
    public record Snapshot(String commitSha,String treeSha,String projectPrefix,List<GitSnapshotInventory.Entry> files) { }
    public Source source(Path project,Selection branch) {
        if(branch==null||branch.ref()==null||!branch.ref().startsWith("refs/heads/")||branch.ref().chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("DOCUMENT_BRANCH_UNAVAILABLE","请选择明确的本地或远程分支");
        var scope=GitProjectScope.require(git,project);
        git.read(scope.project(),"check-ref-format",branch.ref());
        Path gitDirectory;
        try {gitDirectory=scope.project().resolve(git.read(scope.project(),"rev-parse","--git-common-dir").strip()).toRealPath();}
        catch(IOException invalid){throw new BadRequestException("DOCUMENT_CODE_SOURCE_INVALID","无法确认源仓库的对象目录");}
        String remote=scope.repository().toUri().toString();
        if(branch.remote()!=null)remote=git.read(project,"remote","get-url","--",branch.remote()).strip();
        if(remote.isBlank()||remote.startsWith("ext::")||remote.startsWith("-")||remote.chars().anyMatch(Character::isISOControl))
            throw new BadRequestException("DOCUMENT_CODE_SOURCE_INVALID","该来源不支持受控快照读取");
        // A relative filesystem remote is relative to the original checkout, never the new bare store.
        if(!remote.contains(":")&&!Path.of(remote).isAbsolute()&&!remote.startsWith("\\\\"))
            remote=scope.repository().resolve(remote).normalize().toUri().toString();
        return new Source(scope.project(),scope.repository(),gitDirectory,scope.prefix(),remote,branch);
    }
    /** The caller persists this exact SHA before fetching any objects. */
    public String resolve(Source source) {
        var branch=source.branch();
        if(branch.remote()==null)return GitSnapshotInventory.objectId(git.read(source.project(),"rev-parse","--verify",branch.ref()+"^{commit}").strip());
        var result=git.remote(source.project(),source.project(),Duration.ofSeconds(60),List.of("ls-remote","--refs","--",source.remote(),branch.ref()),source.remote());
        result.requireSuccess(List.of("ls-remote"));
        var matches=result.output().lines().map(line->line.split("\t",2)).filter(fields->fields.length==2&&fields[1].equals(branch.ref())).toList();
        if(matches.size()!=1)throw new BadRequestException("DOCUMENT_BRANCH_UNAVAILABLE","无法唯一确定评审分支，请重新选择");
        return GitSnapshotInventory.objectId(matches.getFirst()[0]);
    }
    public Snapshot capture(Source source,Path repository,String commit) {
        return capture(source,repository,commit,source::remote);
    }
    /** Recovered objects need no source access; resolve a current authenticated transport only if fetching is necessary. */
    public Snapshot capture(Source source,Path repository,String commit,java.util.function.Supplier<String> transport) {
        prepareRepository(source,repository,commit);
        var found=git.run(repository,Duration.ofSeconds(10),List.of("cat-file","-e",commit+"^{commit}"));
        if(found.exitCode()!=0) {
            String remote=transport.get();
            var fetched=git.remote(repository,source.project(),Duration.ofSeconds(60),List.of("fetch","--depth=1","--no-tags","--no-write-fetch-head","--",remote,commit+":refs/heads/frozen"),remote);
            fetched.requireSuccess(List.of("fetch"));
        }
        String actual=git.read(repository,"rev-parse","--verify",commit+"^{commit}").strip();
        if(!commit.equals(actual))throw new BadRequestException("DOCUMENT_CODE_SHA_CHANGED","代码来源与冻结提交不一致");
        String tree=GitSnapshotInventory.objectId(git.read(repository,"rev-parse","--verify",source.prefix().isEmpty()?actual+"^{tree}":actual+":"+source.prefix().substring(0,source.prefix().length()-1)).strip());
        return new Snapshot(actual,tree,source.prefix(),GitSnapshotInventory.parse(git.read(repository,"ls-tree","-r","-z","-l","--full-tree",tree)));
    }
    /** Full ancestry is required for history evidence, independently of the shallow tree-only capture. */
    public void captureHistory(Source source,Path repository,String commit,java.util.function.Supplier<String> transport) {
        prepareRepository(source,repository,commit);git.requireSupported(repository);
        requireCompleteHistory(repository);
        var found=git.run(repository,Duration.ofSeconds(10),List.of("cat-file","-e",commit+"^{commit}"));
        if(found.exitCode()!=0) {
            String remote=transport.get();
            var fetched=git.remote(repository,source.project(),Duration.ofSeconds(60),List.of("fetch","--no-tags","--no-write-fetch-head","--",remote,commit+":refs/heads/frozen"),remote);
            fetched.requireSuccess(List.of("fetch"));
        }
        requireCompleteHistory(repository);
        if(!commit.equals(git.read(repository,"rev-parse","--verify",commit+"^{commit}").strip()))
            throw new BadRequestException("DOCUMENT_CODE_SHA_CHANGED","代码来源与冻结提交不一致");
        // A reachable commit alone does not prove that all parents, trees and blobs survived recovery.
        git.read(repository,"rev-list","--objects","--missing=error","--quiet",commit,"--");
    }
    public void requireCompleteHistory(Path repository) {
        if("true".equals(git.read(repository,"rev-parse","--is-shallow-repository").strip()))
            throw new io.opencode.loopper.domain.TaskFailure("TEMPLATE_SHALLOW_SOURCE","所选来源的提交历史不完整，请补齐来源历史后重新发起任务");
    }
    private void prepareRepository(Source source,Path repository,String commit) {
        GitSnapshotInventory.objectId(commit);privateRepository(source,repository);
        if(!Files.exists(repository.resolve("HEAD"),LinkOption.NOFOLLOW_LINKS))
            git.read(repository.getParent(),"init","--bare","--template=","--object-format="+(commit.length()==64?"sha256":"sha1"),"--",repository.toString());
        if(!git.read(repository,"rev-parse","--is-bare-repository").strip().equals("true"))
            throw new BadRequestException("DOCUMENT_CODE_PATH_INVALID","冻结代码目录必须是独立受管的裸仓库");
    }
    private static void privateRepository(Source source,Path repository) {
        try {
            if(!repository.isAbsolute()||Files.isSymbolicLink(repository)||Files.isSymbolicLink(repository.getParent())
                    ||Files.exists(repository.resolve(".git"),LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe repository");
            Path parent=repository.getParent().toRealPath(),candidate=parent.resolve(repository.getFileName());
            if(source.repository().startsWith(candidate)||source.project().startsWith(candidate)
                    ||candidate.startsWith(source.gitDirectory())||source.gitDirectory().startsWith(candidate))throw new IOException("source checkout");
            if(Files.exists(repository,LinkOption.NOFOLLOW_LINKS)&&(!repository.toRealPath().getParent().equals(parent)||!Files.isDirectory(repository,LinkOption.NOFOLLOW_LINKS)))throw new IOException("containment");
        }catch(IOException invalid){throw new BadRequestException("DOCUMENT_CODE_PATH_INVALID","冻结代码目录必须位于受管私有目录，不能覆盖源仓库");}
    }
}
