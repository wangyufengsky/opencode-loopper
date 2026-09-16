package io.opencode.loopper.runtime;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Parses line-porcelain without interpreting repository text as commands or Markdown. */
public final class GitBlameLines {
    private GitBlameLines() { }
    private static final Pattern HEADER = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64}) ([0-9]+) ([0-9]+)(?: [0-9]+)?$");
    public record Line(int number, String commit, String author, String email, Instant authoredAt, String summary) { }

    public static List<Line> parse(String output, int start, int end) {
        List<Line> result = new ArrayList<>();
        String commit = null, author = null, email = null, summary = null;
        Instant time = null; int number = 0;
        for (String line : output.split("\n", -1)) {
            if (commit == null) {
                if (line.isEmpty()) continue;
                var match = HEADER.matcher(line);
                if (!match.matches()) throw new IllegalArgumentException("Invalid blame header");
                commit = match.group(1); number = Integer.parseInt(match.group(3));
                author = null; email = null; summary = null; time = null;
            } else if (line.startsWith("\t")) {
                if (author == null || email == null || summary == null || time == null || number != start + result.size()
                        || number > end || commit.chars().allMatch(c -> c == '0'))
                    throw new IllegalArgumentException("Incomplete blame metadata");
                result.add(new Line(number, commit, author, email, time, summary)); commit = null;
            } else if (line.startsWith("author ")) author = line.substring(7);
            else if (line.startsWith("author-mail ")) email = line.substring(12);
            else if (line.startsWith("author-time ")) time = Instant.ofEpochSecond(Long.parseLong(line.substring(12)));
            else if (line.startsWith("summary ")) summary = line.substring(8);
        }
        if (commit != null || result.size() != (long) end - start + 1) throw new IllegalArgumentException("Incomplete blame range");
        return List.copyOf(result);
    }
}
