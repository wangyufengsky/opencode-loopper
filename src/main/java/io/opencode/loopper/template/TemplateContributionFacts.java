package io.opencode.loopper.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical identities and equal coauthor shares are computed from Git, never inferred by a model. */
public final class TemplateContributionFacts {
    private TemplateContributionFacts() { }

    public static List<Person> people(TemplateGitEvidence evidence) {
        Map<String, Accumulator> people = new LinkedHashMap<>();
        for (var commit : evidence.commits()) {
            double share = 1d / commit.contributors().size();
            for (var author : commit.contributors()) {
                var person = people.computeIfAbsent(author.identity(), ignored -> new Accumulator(author));
                person.commits.add(commit.sha());
                person.evidenceIds.add(commit.sha());
                for (var change : commit.changes()) {
                    person.evidenceIds.add(change.evidenceId());
                    person.rawLines += (change.additions() + change.deletions()) * share;
                    person.effectiveLines += change.effectiveLines() * share;
                }
            }
        }
        return people.values().stream().map(person -> new Person(person.author, List.copyOf(person.commits),
                Set.copyOf(person.evidenceIds), person.rawLines, person.effectiveLines))
                .sorted(java.util.Comparator.comparing(person -> person.author().identity())).toList();
    }

    public record Person(TemplateGitEvidence.Contributor author, List<String> commits, Set<String> evidenceIds,
                         double rawLines, double effectiveLines) {
        public Person {
            commits = List.copyOf(commits);
            evidenceIds = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(evidenceIds));
        }
    }

    private static final class Accumulator {
        private final TemplateGitEvidence.Contributor author;
        private final List<String> commits = new ArrayList<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private double rawLines;
        private double effectiveLines;
        private Accumulator(TemplateGitEvidence.Contributor author) { this.author = author; }
    }
}
