package io.opencode.loopper.service;

import io.opencode.loopper.persistence.SourceTemplateModelMapper;
import io.opencode.loopper.template.SourceDesign;
import java.util.*;
import java.util.function.Function;

/** Pure candidate semantics over frozen identities and this role's independently recorded reads. */
final class SourceDesignValidation {
    private SourceDesignValidation() { }
    static SourceDesign.Candidate design(SourceDesign.Input input, SourceDesign.Candidate value,
            Function<String, List<SourceTemplateModelMapper.Read>> reads) {
        if (value == null) throw invalid("/", "请提交完整的详细设计");
        text(value.title(), 200, "/title"); text(value.summary(), 4000, "/summary");
        if (value.sections() == null || value.sections().isEmpty() || value.sections().size() > 32)
            throw invalid("/sections", "需要 1–32 个设计章节");
        limitations(value.limitations());
        var covered = new HashSet<String>(); var keys = new HashSet<String>();
        for (int index = 0; index < value.sections().size(); index++) {
            var section = value.sections().get(index); String pointer = "/sections/" + index;
            if (section == null) throw invalid(pointer, "章节不能为空");
            text(section.key(), 80, pointer + "/key");
            if (!section.key().matches("[A-Za-z0-9_-]+") || !keys.add(section.key()))
                throw invalid(pointer + "/key", "章节编号必须唯一，仅使用字母、数字、下划线和连字符");
            text(section.title(), 200, pointer + "/title"); text(section.markdown(), 48000, pointer + "/markdown");
            SourceDesignMarkdown.validate(section.markdown());
            if (section.paths() == null || section.paths().isEmpty() || section.paths().size() > 100
                    || new HashSet<>(section.paths()).size() != section.paths().size() || !input.paths().containsAll(section.paths()))
                throw invalid(pointer + "/paths", "章节必须关联本批实际分配的源码路径，不能重复或扩大处理范围");
            references(section.references(), section.paths(), reads, pointer + "/references");
            covered.addAll(section.paths());
        }
        complete(input.paths(), covered, "/sections");
        fullReads(input.paths(), reads);
        return value;
    }
    static SourceDesign.Review review(SourceDesign.Input input, SourceDesign.Review value,
            Function<String, List<SourceTemplateModelMapper.Read>> reads) {
        if (value == null || !Set.of("PASS", "REVISE").contains(Objects.toString(value.verdict(), "")))
            throw invalid("/verdict", "复核结论必须是 PASS 或 REVISE");
        text(value.reason(), 4000, "/reason");
        if (value.checkedPaths() == null || value.checkedPaths().size() != new HashSet<>(value.checkedPaths()).size())
            throw invalid("/checkedPaths", "请列出无重复的已复核源码路径");
        complete(input.paths(), new HashSet<>(value.checkedPaths()), "/checkedPaths");
        fullReads(input.paths(), reads);
        references(value.references(), input.paths(), reads, "/references");
        if (value.issues() == null || value.issues().size() > 64) throw invalid("/issues", "请提交问题清单，最多 64 项");
        if (value.verdict().equals("PASS") != value.issues().isEmpty())
            throw invalid("/verdict", "存在问题必须要求修订；要求修订必须说明具体问题");
        for (var issue : value.issues()) {
            if (issue == null) throw invalid("/issues", "问题不能为空");
            text(issue.sectionKey(), 80, "/issues/sectionKey"); text(issue.detail(), 4000, "/issues/detail");
            text(issue.recommendation(), 4000, "/issues/recommendation");
        }
        return value;
    }
    private static void references(List<SourceDesign.Reference> references, List<String> paths,
            Function<String, List<SourceTemplateModelMapper.Read>> reads, String pointer) {
        if (references == null || references.isEmpty() || references.size() > 100) throw invalid(pointer, "请提供实际读取的源码引用");
        var referenced = new HashSet<String>();
        for (var ref : references) {
            if (ref == null || !paths.contains(ref.path()) || ref.sha256() == null
                    || ref.startLine() < 1 || ref.endLine() < ref.startLine()) throw invalid(pointer, "引用路径或行号无效");
            text(ref.quote(), 8000, pointer + "/quote");
            boolean verified = reads.apply(ref.path()).stream().anyMatch(read -> {
                if (!read.sha256().equals(ref.sha256()) || read.startLine() > ref.startLine() || read.endLine() < ref.endLine()) return false;
                var lines = read.content().lines().toList();
                int first = ref.startLine() - read.startLine(), end = ref.endLine() - read.startLine() + 1;
                return end <= lines.size() && String.join("\n", lines.subList(first, end)).strip().equals(ref.quote().strip());
            });
            if (!verified) throw invalid(pointer, "引用必须与本角色实际读取的冻结源码、哈希和行范围一致");
            referenced.add(ref.path());
        }
        if (!referenced.containsAll(paths)) throw invalid(pointer, "每个关联源码文件至少需要一条有效引用");
    }
    private static void fullReads(List<String> paths, Function<String, List<SourceTemplateModelMapper.Read>> reads) {
        for (String path : paths) {
            var intervals = reads.apply(path).stream().sorted(Comparator.comparingInt(SourceTemplateModelMapper.Read::startLine)).toList();
            int next = 1, total = -1; String hash = null;
            for (var read : intervals) {
                if (total < 0) { total = read.totalLines(); hash = read.sha256(); }
                if (read.totalLines() != total || !read.sha256().equals(hash) || read.startLine() > next) break;
                next = Math.max(next, read.endLine() + 1);
            }
            if (total < 0 || next <= total) throw invalid("/coverage", "尚未完整读取分配源码：" + path);
        }
    }
    private static void complete(List<String> required, Set<String> actual, String pointer) {
        if (!new HashSet<>(required).equals(actual)) throw invalid(pointer, "覆盖清单必须包含本批全部源码，不能遗漏或增加其他路径");
    }
    private static void limitations(List<String> items) {
        if (items == null || items.size() > 32) throw invalid("/limitations", "请提供局限清单，最多 32 项");
        items.forEach(item -> text(item, 2000, "/limitations"));
    }
    private static void text(String value, int maximum, String pointer) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
            throw invalid(pointer, "文本不能为空或超过字段长度上限");
    }
    private static DocumentCandidateProblem invalid(String pointer, String message) {
        return new DocumentCandidateProblem("SOURCE_DESIGN_INVALID", pointer, message);
    }
}
