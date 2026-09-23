package io.opencode.loopper.service;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.*;
import javax.tools.*;

/** Existing Java members must retain their syntax tree; adding comments or guards cannot hide assertions. */
final class SourceExistingTests {
    private SourceExistingTests() { }
    static boolean preserved(String path, String before, String after) {
        if (before.equals(after)) return true;
        // Without a language parser, keep the old file and put additional scenarios in a separate test file.
        if (!path.endsWith(".java")) return false;
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return false;
        try (var files = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var previous = parse(compiler, files, before); var current = parse(compiler, files, after);
            if (!Objects.equals(text(previous.getPackage()), text(current.getPackage()))) return false;
            var oldImports = previous.getImports().stream().map(Object::toString).toList();
            var newImports = current.getImports().stream().map(Object::toString).toList();
            if (!newImports.containsAll(oldImports)) return false;
            for (var imported : current.getImports()) if (!oldImports.contains(imported.toString())) {
                String name = imported.getQualifiedIdentifier().toString();
                if (name.endsWith(".*")) return false;
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (previous.getImports().stream().anyMatch(i -> i.getQualifiedIdentifier().toString().endsWith("." + simple))) return false;
            }
            var oldTypes = classes(previous); var newTypes = classes(current);
            for (var type : oldTypes.entrySet()) {
                var next = newTypes.get(type.getKey());
                if (next == null || !preserved(type.getValue(), next)) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }
    private static CompilationUnitTree parse(JavaCompiler compiler, StandardJavaFileManager files, String content) throws Exception {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///ExistingTest.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return content; }
        };
        var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(file));
        var unit = task.parse().iterator().next();
        if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
            throw new IllegalArgumentException("Invalid test syntax");
        return unit;
    }
    private static Map<String, ClassTree> classes(CompilationUnitTree unit) {
        var result = new LinkedHashMap<String, ClassTree>();
        for (var type : unit.getTypeDecls()) if (type instanceof ClassTree declaration)
            result.put(declaration.getSimpleName().toString(), declaration);
        return result;
    }
    private static boolean preserved(ClassTree previous, ClassTree current) {
        if (previous.getKind() != current.getKind() || !text(previous.getModifiers()).equals(text(current.getModifiers()))
                || !text(previous.getExtendsClause()).equals(text(current.getExtendsClause()))
                || !previous.getImplementsClause().toString().equals(current.getImplementsClause().toString())
                || !previous.getTypeParameters().toString().equals(current.getTypeParameters().toString())
                || !previous.getPermitsClause().toString().equals(current.getPermitsClause().toString())) return false;
        var remaining = new ArrayList<Tree>(current.getMembers());
        for (var member : previous.getMembers()) {
            int index = -1;
            for (int i = 0; i < remaining.size(); i++) if (member.toString().equals(remaining.get(i).toString())) { index = i; break; }
            if (index < 0) return false;
            remaining.remove(index);
        }
        for (var added : remaining) {
            if (added instanceof BlockTree) return false;
            if (added instanceof MethodTree method && (method.getReturnType() == null
                    || method.getModifiers().getAnnotations().stream().anyMatch(a -> a.getAnnotationType().toString()
                        .matches("(?:.*\\.)?(?:Before.*|After.*|Ignore|Disabled|RegisterExtension)")))) return false;
        }
        return true;
    }
    private static String text(Tree tree) { return tree == null ? "" : tree.toString(); }
}
