package io.opencode.loopper.workflow;

import java.nio.charset.StandardCharsets;

/** Portable archive member names; historical packages retain their existing flat ASCII contract. */
public final class WorkflowDocumentPaths {
    private static final java.util.regex.Pattern UNSAFE=java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}<>:\"\\\\|?*]");
    private WorkflowDocumentPaths(){ }
    private static boolean hierarchical(String type){return WorkflowHistoryReport.TYPE.equals(type)||WorkflowSnapshotReport.TYPE.equals(type);}
    public static int limit(String type){return hierarchical(type)?10000:1026;}
    public static int manifestLimit(String type){return hierarchical(type)?4*1024*1024:1024*1024;}
    public static void require(String type,String path) {
        if(!hierarchical(type)) {
            if(path==null||!path.matches("[A-Za-z0-9_.-]{1,160}")||path.equals(".")||path.equals(".."))throw new IllegalArgumentException("文档文件名无效。");return;
        }
        if(path==null||path.length()>1024||!path.endsWith(".md")||UNSAFE.matcher(path).find())throw new IllegalArgumentException("历史报告路径无效。");
        String[] parts=path.split("/",-1);if(parts.length<2||parts.length>3)throw new IllegalArgumentException("历史报告目录层级无效。");
        for(String part:parts)if(part.isBlank()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" ")||part.getBytes(StandardCharsets.UTF_8).length>255)
            throw new IllegalArgumentException("历史报告文件名不适合跨平台保存。");
    }
}
