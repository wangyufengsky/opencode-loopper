package io.opencode.loopper.template;

import java.util.List;

/** Frozen build facts and writable test roots. Commands remain native verifier inputs. */
public record SourceTestProfile(String manifestSha256, List<Module> modules) {
    public record Module(String root, String framework, List<String> sourcePaths,
                         List<String> testRoots, List<String> fixtureRoots, List<String> command) { }
}
