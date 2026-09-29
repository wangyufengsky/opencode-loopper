package io.opencode.loopper.workflow;

import java.util.*;

/** Pure, versioned file assertions. This adapter cannot launch a process or access a network. */
public record WorkflowVerification(int version,String inputName,List<Check> checks) {
    public static final String MODULE="system.verify.files",ADAPTER="system.verify.files.v1";
    public record Check(String title,String type,String path,String expected,String matchMode) { }
    public WorkflowVerification { checks=checks==null?List.of():List.copyOf(checks); }
    public void validate() {
        if(version!=1 || inputName==null || !inputName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || checks.isEmpty() || checks.size()>16)
            throw invalid("请选择代码输入，并配置 1–16 项交付物检查。");
        for(var check:checks) {
            if(check.title()==null || check.title().isBlank() || check.title().length()>120 || check.path()==null
                    || check.path().isBlank() || check.path().length()>1024 || check.path().contains("\\") || check.path().startsWith("/")
                    || check.path().contains(":") || check.path().chars().anyMatch(Character::isISOControl)
                    || Arrays.stream(check.path().split("/",-1)).anyMatch(part->part.isEmpty() || part.equals(".") || part.equals("..")))
                throw invalid("检查名称或项目内相对文件路径无效。");
            if(check.type()==null || !Set.of("FILE_CONTENT","FILE_HASH","FILE_NOT_EXISTS").contains(check.type()))
                throw invalid("此模块支持内容、哈希和文件移除检查；进程及网络检查需要对应执行模块。");
            if(check.type().equals("FILE_CONTENT") && (check.expected()==null || check.expected().isBlank() || check.expected().length()>4000
                    || check.matchMode()==null || !Set.of("EXACT","CONTAINS").contains(check.matchMode())))
                throw invalid("内容检查需要期望文本和精确匹配或包含方式。");
            if(check.type().equals("FILE_HASH") && (check.expected()==null || !check.expected().matches("[0-9a-fA-F]{64}")))
                throw invalid("哈希检查需要完整的 SHA-256。");
            if(check.type().equals("FILE_NOT_EXISTS") && check.expected()!=null && !check.expected().isEmpty())throw invalid("文件移除检查不需要期望文本。");
        }
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
