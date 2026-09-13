package io.opencode.loopper.service.assist;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** No symlink traversal, bounded directory visits, explicit external log roots only. */
public final class EvidenceSourceScanner {
    public record FileSource(Path root, Path path, String kind, String identity, long size, String sha) { }
    public record Scan(List<FileSource> files, boolean complete, List<String> issues) { }
    private EvidenceSourceScanner() { }
    public static Path externalRoot(String value) {
        try {
            Path input=Path.of(value);
            if(!input.isAbsolute() || !input.normalize().equals(input) || input.getNameCount()<2) throw denied();
            Path current=input.getRoot();
            for(Path part:input) {
                current=current.resolve(part);
                if(Files.isSymbolicLink(current) || sensitive(part.toString())) throw denied();
            }
            if(!Files.isDirectory(input,LinkOption.NOFOLLOW_LINKS)) throw denied();
            return input.toRealPath();
        } catch(IOException|InvalidPathException failure) { throw denied(); }
    }
    public static Scan scan(Path workspace,List<BatchAssistConfigService.Source> sources) {
        List<FileSource> files=new ArrayList<>(); List<String> issues=new ArrayList<>(); Set<Path> seen=new HashSet<>();
        int[] visited={0}; long[] readBytes={0}; long deadline=System.nanoTime()+2_000_000_000L;
        for(var source:sources) {
            try {
                Path root=source.root()==null||source.root().isBlank()?workspace.toRealPath():externalRoot(source.root());
                PathMatcher matcher=FileSystems.getDefault().getPathMatcher("glob:"+source.pattern());
                int before=files.size();
                Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),12,new SimpleFileVisitor<>() {
                    private FileVisitResult budget() {
                        if(++visited[0]>4096 || System.nanoTime()>deadline || files.size()>=64) {
                            if(!issues.contains("扫描达到数量或时间上限")) issues.add("扫描达到数量或时间上限");
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs) {
                        if(!path.equals(root) && sensitive(path.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                        return budget();
                    }
                    @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs) {
                        if(budget()==FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                        if(!attrs.isRegularFile() || !matcher.matches(root.relativize(path)) || sensitive(path.getFileName().toString()) || !seen.add(path)) return FileVisitResult.CONTINUE;
                        Path safe=AssistFiles.resolve(root,root.relativize(path).toString().replace('\\','/'));
                        String sha="";
                        if(source.kind().equals("JUNIT") && attrs.size()<=4_000_000) {
                            if(readBytes[0]+attrs.size()>32L*1024*1024) {issues.add("扫描达到字节上限");return FileVisitResult.TERMINATE;}
                            readBytes[0]+=attrs.size();sha=AssistFiles.sha(AssistFiles.read(safe,4_000_000));
                        }
                        files.add(new FileSource(root,safe,source.kind(),Objects.toString(attrs.fileKey(),"")+":"+attrs.creationTime(),attrs.size(),sha));
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file,IOException error) { issues.add("部分文件不可读取"); return budget(); }
                });
                if(files.size()==before) issues.add("规则未找到可采集文件："+source.pattern());
            } catch(IOException|RuntimeException failure) { issues.add("证据来源不可读取，请检查目录权限和路径规则"); }
        }
        return new Scan(List.copyOf(files),issues.stream().allMatch(issue->issue.startsWith("规则未找到")),issues.stream().distinct().limit(20).toList());
    }
    private static boolean sensitive(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        return n.startsWith(".") || n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".p12")
                || n.equals("credentials") || n.equals("secrets") || n.equals("node_modules");
    }
    private static AssistFailure denied() { return new AssistFailure("EVIDENCE_ROOT_FORBIDDEN","请选择明确的普通日志目录，不能使用根目录、敏感目录或符号链接"); }
}
