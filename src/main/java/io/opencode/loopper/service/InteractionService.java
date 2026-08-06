package io.opencode.loopper.service;

import io.opencode.loopper.api.FeatureContracts;
import io.opencode.loopper.domain.InteractionAction;
import io.opencode.loopper.domain.InteractionKind;
import io.opencode.loopper.domain.InteractionState;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.InteractionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class InteractionService {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final ObjectMapper json;
    private final TaskEventService taskEvents;
    private final Object interactionLock = new Object();

    public InteractionService(LoopperMapper mapper, OpenCodeClient openCode, ObjectMapper json,
                              TaskEventService taskEvents) {
        this.mapper = mapper;
        this.openCode = openCode;
        this.json = json;
        this.taskEvents = taskEvents;
    }

    /** Refreshes provider state before returning the persisted, server-authoritative Inbox. */
    public List<FeatureContracts.InteractionDto> listOpen() {
        synchronized (interactionLock) {
            refreshActiveSessionsLocked();
            return mapper.openInteractions().stream().map(this::dto).toList();
        }
    }

    public FeatureContracts.InteractionDto resolve(String id, FeatureContracts.ResolveInteractionRequest request) {
        synchronized (interactionLock) {
            return resolveLocked(id, request);
        }
    }

    private FeatureContracts.InteractionDto resolveLocked(String id, FeatureContracts.ResolveInteractionRequest request) {
        if (request == null || request.action() == null || request.version() == null) {
            throw new BadRequestException("INTERACTION_RESOLUTION_TOKEN_REQUIRED",
                    "Interaction action and version are required");
        }
        InteractionRow original = mapper.findInteraction(id)
                .orElseThrow(() -> new NotFoundException("Interaction not found: " + id));
        InteractionKind kind = InteractionKind.valueOf(original.kind());
        if (!request.action().allowedFor(kind)) {
            throw new BadRequestException("INTERACTION_ACTION_INVALID",
                    request.action() + " is not valid for " + kind);
        }
        if (hardDeny(original) != null && request.action() != InteractionAction.REJECT) {
            throw new ConflictException("PERMISSION_HARD_DENIED",
                    "This permission is denied by the non-overridable local safety policy");
        }
        String claimedAt = now();
        if (mapper.claimInteraction(id, request.version(), claimedAt) != 1) {
            throw new ConflictException("INTERACTION_VERSION_CONFLICT",
                    "Interaction was already resolved or changed; refresh the Inbox");
        }
        InteractionRow claimed = mapper.findInteraction(id)
                .orElseThrow(() -> new NotFoundException("Interaction not found after claim: " + id));
        try {
            OpenCodeClient.OpenCodeSession remote = remote(claimed);
            if (kind == InteractionKind.QUESTION) {
                if (request.action() == InteractionAction.REJECT) {
                    openCode.rejectQuestion(remote, claimed.externalRequestId());
                } else {
                    if (request.answers().isEmpty()) {
                        throw new BadRequestException("QUESTION_ANSWERS_REQUIRED", "Question answers are required");
                    }
                    openCode.replyQuestion(remote, claimed.externalRequestId(), request.answers());
                }
            } else {
                OpenCodeClient.PermissionReply reply = switch (request.action()) {
                    case ONCE -> OpenCodeClient.PermissionReply.ONCE;
                    case SESSION -> OpenCodeClient.PermissionReply.SESSION;
                    case REJECT -> OpenCodeClient.PermissionReply.REJECT;
                    default -> throw new BadRequestException("PERMISSION_ACTION_INVALID", "Permission action is invalid");
                };
                if (reply != OpenCodeClient.PermissionReply.REJECT) {
                    rejectIfLivePermissionTurnedDangerous(claimed, remote);
                }
                openCode.replyPermission(remote, claimed.externalRequestId(), reply, request.message());
            }
            String resolvedAt = now();
            InteractionState state = request.action() == InteractionAction.REJECT
                    ? InteractionState.REJECTED : InteractionState.RESOLVED;
            InteractionRow resolved = new InteractionRow(claimed.id(), claimed.scopeType(), claimed.scopeId(),
                    claimed.taskId(), claimed.designerSessionId(), claimed.localSessionId(),
                    claimed.externalSessionId(), claimed.externalRequestId(), claimed.kind(), state.name(),
                    claimed.payloadJson(), request.action().name(), write(Map.of(
                    "answers", request.answers(), "message", request.message() == null ? "" : request.message())),
                    claimed.createdAt(), resolvedAt, resolvedAt, claimed.version());
            if (mapper.resolveInteraction(resolved) != 1) {
                throw new ConflictException("INTERACTION_VERSION_CONFLICT",
                        "Interaction changed while the provider response was being persisted");
            }
            if (claimed.taskId() != null) {
                taskEvents.emit(claimed.taskId(), "interaction.resolved", Map.of(
                        "interactionId", claimed.id(), "kind", claimed.kind(), "action", request.action().name()));
            }
            return dto(mapper.findInteraction(id).orElseThrow());
        } catch (RuntimeException failure) {
            releaseClaimIfStillResolving(id, claimed.version());
            throw failure;
        }
    }

    public void refreshActiveSessions() {
        synchronized (interactionLock) {
            refreshActiveSessionsLocked();
        }
    }

    private void refreshActiveSessionsLocked() {
        // A provider request cannot be acted on after its local writer has reached a terminal state.
        // Reconcile these rows before listing the Inbox so a crashed/timed-out task cannot leave
        // an indefinitely actionable-looking permission behind.
        mapper.markTerminalSessionInteractionsStale(now());
        for (ExecutionSessionRow session : mapper.activeExecutionSessions()) {
            if (session.externalSessionId() == null) continue;
            TaskRow task = mapper.findTask(session.taskId()).orElse(null);
            if (task == null || task.worktreePath() == null) continue;
            refresh("TASK", task.id(), task.id(), null, session.id(), session.externalSessionId(),
                    Path.of(task.worktreePath()));
        }
        for (DesignerSessionRow session : mapper.activeDesignerHandoffs()) {
            if (session.externalSessionId() == null) continue;
            var project = mapper.findProject(session.projectId()).orElse(null);
            if (project == null) continue;
            refresh("DESIGNER", session.id(), null, session.id(), session.id(), session.externalSessionId(),
                    Path.of(project.rootPath()));
        }
    }

    private void refresh(String scopeType, String scopeId, String taskId, String designerSessionId,
                         String localSessionId, String externalSessionId, Path directory) {
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(externalSessionId, directory);
        List<String> activeIds = new ArrayList<>();
        try {
            for (OpenCodeClient.PendingQuestion question : openCode.pendingQuestions(remote)) {
                activeIds.add(question.id());
                persist(scopeType, scopeId, taskId, designerSessionId, localSessionId, externalSessionId,
                        question.id(), InteractionKind.QUESTION, InteractionState.PENDING,
                        Map.of("questions", question.questions()));
            }
            for (OpenCodeClient.PendingPermission permission : openCode.pendingPermissions(remote)) {
                activeIds.add(permission.id());
                String denyReason = hardDeny(permission, directory);
                Map<String, Object> payload = permissionPayload(permission, denyReason);
                persist(scopeType, scopeId, taskId, designerSessionId, localSessionId, externalSessionId,
                        permission.id(), InteractionKind.PERMISSION,
                        denyReason == null ? InteractionState.PENDING : InteractionState.HARD_DENIED, payload);
                if (denyReason != null) {
                    try {
                        openCode.replyPermission(remote, permission.id(), OpenCodeClient.PermissionReply.REJECT,
                                denyReason);
                    } catch (RuntimeException ignored) {
                        // Keep the hard-denied row visible and retry while the provider still reports it.
                    }
                }
            }
            mapper.markMissingInteractionsStale(externalSessionId, write(activeIds), now());
        } catch (RuntimeException ignored) {
            // Transport failure must not erase the last persisted pending state.
        }
    }

    /** Re-checks the provider immediately before granting ONCE/SESSION, closing payload-change TOCTOU windows. */
    private void rejectIfLivePermissionTurnedDangerous(InteractionRow claimed, OpenCodeClient.OpenCodeSession remote) {
        OpenCodeClient.PendingPermission live = openCode.pendingPermissions(remote).stream()
                .filter(permission -> claimed.externalRequestId().equals(permission.id())).findFirst().orElse(null);
        if (live == null) {
            throw new ConflictException("INTERACTION_VERSION_CONFLICT",
                    "Permission is no longer pending at the provider; refresh the Inbox");
        }
        String denyReason = hardDeny(live, remote.worktree());
        if (denyReason == null) return;
        persist(claimed.scopeType(), claimed.scopeId(), claimed.taskId(), claimed.designerSessionId(),
                claimed.localSessionId(), claimed.externalSessionId(), live.id(), InteractionKind.PERMISSION,
                InteractionState.HARD_DENIED, permissionPayload(live, denyReason));
        openCode.replyPermission(remote, live.id(), OpenCodeClient.PermissionReply.REJECT, denyReason);
        throw new ConflictException("PERMISSION_HARD_DENIED",
                "This permission is denied by the non-overridable local safety policy");
    }

    private void releaseClaimIfStillResolving(String id, long version) {
        InteractionRow current = mapper.findInteraction(id).orElse(null);
        if (current != null && InteractionState.RESOLVING.name().equals(current.state()) && current.version() == version) {
            mapper.releaseInteractionClaim(id, version, now());
        }
    }

    private Map<String, Object> permissionPayload(OpenCodeClient.PendingPermission permission, String denyReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permission", permission.permission());
        payload.put("patterns", permission.patterns());
        payload.put("metadata", permission.metadata());
        payload.put("title", permission.title() == null ? "" : permission.title());
        payload.put("hardDenied", denyReason != null);
        if (denyReason != null) payload.put("hardDenyReason", denyReason);
        return payload;
    }

    private void persist(String scopeType, String scopeId, String taskId, String designerSessionId,
                         String localSessionId, String externalSessionId, String requestId,
                         InteractionKind kind, InteractionState state, Object payload) {
        String timestamp = now();
        mapper.upsertInteraction(new InteractionRow(UUID.randomUUID().toString(), scopeType, scopeId, taskId,
                designerSessionId, localSessionId, externalSessionId, requestId, kind.name(), state.name(),
                write(payload), null, null, timestamp, timestamp, null, 0));
    }

    private String hardDeny(InteractionRow row) {
        if (!InteractionKind.PERMISSION.name().equals(row.kind())) return null;
        try {
            JsonNode payload = json.readTree(row.payloadJson());
            if (payload.path("hardDenied").asBoolean(false)) {
                return payload.path("hardDenyReason").asText("Denied by local safety policy");
            }
            String dangerous = hardDenyText(payload.toString());
            if (dangerous != null) return dangerous;
            return externalWorkspacePathDeny(payload, workspaceFor(row));
        } catch (JacksonException invalidPayload) {
            return "Malformed permission payload is denied by local safety policy";
        }
    }

    private String hardDeny(OpenCodeClient.PendingPermission permission, Path workspace) {
        StringBuilder text = new StringBuilder(permission.permission() == null ? "" : permission.permission());
        permission.patterns().forEach(pattern -> text.append(' ').append(pattern));
        permission.metadata().forEach((key, value) -> text.append(' ').append(key).append(' ').append(value));
        String dangerous = hardDenyText(text.toString());
        if (dangerous != null) return dangerous;
        List<PathToken> paths = new ArrayList<>();
        // Only bash patterns are command snippets. Read/edit/write patterns are
        // file paths, so an absolute system executable there is still external data.
        boolean shellCommand = "bash".equalsIgnoreCase(permission.permission());
        permission.patterns().forEach(pattern -> paths.addAll(pathTokens(pattern, shellCommand)));
        strings(permission.metadata()).forEach(value -> paths.addAll(pathTokens(value, false)));
        return externalWorkspacePathDeny(paths, workspace);
    }

    private String hardDenyText(String source) {
        String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> tokens = List.of(normalized.replaceAll("[^a-z0-9_./:=+\\-]+", " ").trim().split(" +"));
        if (tokens.contains("external_directory") || adjacent(tokens, "external", "directory")) return "外部目录访问不可授权";
        int git = commandIndex(tokens, "git");
        if (git >= 0 && containsAfter(tokens, git, "push")) return "git push 不可由运行 Session 授权";
        if (git >= 0 && containsAfter(tokens, git, "reset") && containsAfter(tokens, git, "--hard")) return "hard reset 不可授权";
        int rm = commandIndex(tokens, "rm");
        boolean recursiveRm = rm >= 0 && tokens.subList(rm + 1, tokens.size()).stream().anyMatch(token ->
                "--recursive".equals(token) || shortFlagContains(token, 'r'));
        boolean findDelete = commandIndex(tokens, "find") >= 0 && tokens.contains("-delete");
        boolean windowsRecursiveDelete = (commandIndex(tokens, "rmdir") >= 0 || commandIndex(tokens, "del") >= 0)
                && tokens.contains("/s");
        boolean powershellRecursiveDelete = tokens.contains("remove-item")
                && (tokens.contains("-recurse") || tokens.contains("-recursive"));
        if (recursiveRm || findDelete || windowsRecursiveDelete || powershellRecursiveDelete) {
            return "危险删除不可授权";
        }
        return null;
    }

    /**
     * The provider's generic {@code bash} permission does not reliably label external-directory access.
     * Treat explicit paths as data, not as a provider policy name: a path is allowed only when its canonical
     * location remains inside the task worktree (or Designer project root).  Standard system executables are
     * intentionally excluded so commands such as {@code /usr/bin/git status} remain usable.
     */
    private String externalWorkspacePathDeny(JsonNode payload, Path workspace) {
        List<PathToken> paths = new ArrayList<>();
        payload.path("patterns").forEach(pattern -> paths.addAll(pathTokens(pattern.asText(), true)));
        strings(payload.path("metadata")).forEach(value -> paths.addAll(pathTokens(value, false)));
        return externalWorkspacePathDeny(paths, workspace);
    }

    private String externalWorkspacePathDeny(Collection<PathToken> paths, Path workspace) {
        for (PathToken candidate : paths) {
                if (standardExecutable(candidate)) continue;
                if (workspace == null || !withinWorkspace(candidate.value(), workspace)) {
                    return "工作区外目录访问不可授权";
                }
        }
        return null;
    }

    private List<PathToken> pathTokens(String source, boolean shellCommand) {
        List<PathToken> result = new ArrayList<>();
        if (source == null || source.isBlank()) return result;
        String separated = source.replace("&&", " ; ").replace("||", " ; ")
                .replace("|", " ; ").replace(";", " ; ");
        boolean commandPosition = shellCommand;
        for (String raw : separated.split("\\s+")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            if (";".equals(token)) { commandPosition = shellCommand; continue; }
            token = token.replaceAll("^[\\\"']+|[\\\"',;]+$", "");
            String redirected = redirectionTarget(token);
            if (redirected != null) {
                String target = redirected.replaceAll("^[\\\"']+|[\\\"',;]+$", "");
                if (looksLikePath(target)) result.add(new PathToken(target, false));
                token = token.substring(0, token.length() - redirected.length())
                        .replaceFirst("(?:\\d+)?(?:>>?|<)$", "");
                if (token.isEmpty()) continue;
            }
            int equals = token.indexOf('=');
            boolean assignment = commandPosition && equals > 0 && !token.startsWith("/");
            if (equals >= 0 && equals + 1 < token.length()) token = token.substring(equals + 1);
            if (token.startsWith("file://")) token = token.substring("file://".length());
            boolean commandWord = commandPosition && !assignment;
            if (looksLikePath(token)) result.add(new PathToken(token, commandWord));
            if (commandWord) commandPosition = false;
        }
        return result;
    }

    /** Returns a shell redirection target even when it is attached to the preceding word, e.g. {@code x>/tmp/a}. */
    private String redirectionTarget(String token) {
        int output = token.lastIndexOf('>');
        int input = token.lastIndexOf('<');
        int marker = Math.max(output, input);
        if (marker < 0 || marker + 1 >= token.length()) return null;
        String target = token.substring(marker + 1);
        if (target.startsWith("&")) return null; // descriptor duplication, not a filesystem path
        return target;
    }

    private boolean looksLikePath(String token) {
        return token.startsWith("/") || token.startsWith("~/") || token.equals("..") || token.startsWith("../")
                || token.contains("/../");
    }

    private boolean standardExecutable(PathToken token) {
        if (!token.commandWord() || !token.value().startsWith("/")) return false;
        Path executable;
        try { executable = Path.of(token.value()).normalize(); }
        catch (RuntimeException invalid) { return false; }
        String value = executable.toString();
        boolean trustedBin = value.startsWith("/usr/bin/") || value.startsWith("/bin/") || value.startsWith("/usr/sbin/")
                || value.startsWith("/sbin/") || value.startsWith("/usr/local/bin/") || value.startsWith("/opt/homebrew/bin/");
        return trustedBin && Files.isExecutable(executable);
    }

    private boolean withinWorkspace(String token, Path workspace) {
        Path canonicalWorkspace = canonical(workspace);
        if (canonicalWorkspace == null) return false;
        try {
            Path requested = token.startsWith("~/")
                    ? Path.of(System.getProperty("user.home"), token.substring(2))
                    : Path.of(token);
            Path candidate = requested.isAbsolute() ? requested.normalize() : canonicalWorkspace.resolve(requested).normalize();
            Path canonicalCandidate = canonicalCandidate(candidate);
            return canonicalCandidate != null && canonicalCandidate.startsWith(canonicalWorkspace);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private Path canonical(Path path) {
        try { return path.toRealPath(); }
        catch (IOException missing) { return path.toAbsolutePath().normalize(); }
    }

    /** Resolves existing parents too, preventing an existing symlink from escaping the workspace. */
    private Path canonicalCandidate(Path candidate) {
        List<Path> missing = new ArrayList<>();
        Path probe = candidate;
        while (probe != null && !Files.exists(probe)) {
            Path name = probe.getFileName();
            if (name != null) missing.addFirst(name);
            probe = probe.getParent();
        }
        if (probe == null) return null;
        Path resolved;
        try { resolved = probe.toRealPath(); }
        catch (IOException unreadable) { return null; }
        for (Path segment : missing) resolved = resolved.resolve(segment);
        return resolved.normalize();
    }

    private Path workspaceFor(InteractionRow row) {
        try {
            if (row.taskId() != null) {
                TaskRow task = mapper.findTask(row.taskId()).orElse(null);
                return task == null || task.worktreePath() == null ? null : Path.of(task.worktreePath());
            }
            if (row.designerSessionId() == null) return null;
            DesignerSessionRow session = mapper.findDesignerSession(row.designerSessionId()).orElse(null);
            if (session == null) return null;
            return mapper.findProject(session.projectId()).map(project -> Path.of(project.rootPath())).orElse(null);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private List<String> strings(Object source) {
        List<String> result = new ArrayList<>();
        collectStrings(source, result);
        return result;
    }

    private void collectStrings(Object source, List<String> target) {
        if (source == null) return;
        if (source instanceof String value) { target.add(value); return; }
        if (source instanceof JsonNode node) {
            if (node.isTextual()) target.add(node.asText());
            else if (node.isArray() || node.isObject()) node.forEach(child -> collectStrings(child, target));
            return;
        }
        if (source instanceof Map<?, ?> map) { map.forEach((key, value) -> { collectStrings(key, target); collectStrings(value, target); }); return; }
        if (source instanceof Iterable<?> iterable) iterable.forEach(value -> collectStrings(value, target));
    }

    private record PathToken(String value, boolean commandWord) { }

    private boolean adjacent(List<String> tokens, String left, String right) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (left.equals(tokens.get(index)) && right.equals(tokens.get(index + 1))) return true;
        }
        return false;
    }

    private int commandIndex(List<String> tokens, String command) {
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
            String basename = slash < 0 ? token : token.substring(slash + 1);
            if (command.equals(basename)) return index;
        }
        return -1;
    }

    private boolean containsAfter(List<String> tokens, int index, String expected) {
        return tokens.subList(Math.min(index + 1, tokens.size()), tokens.size()).contains(expected);
    }

    private boolean shortFlagContains(String token, char flag) {
        return token.startsWith("-") && !token.startsWith("--") && token.indexOf(flag, 1) >= 1;
    }

    private OpenCodeClient.OpenCodeSession remote(InteractionRow row) {
        if (row.taskId() != null) {
            TaskRow task = mapper.findTask(row.taskId())
                    .orElseThrow(() -> new NotFoundException("Task not found for interaction: " + row.taskId()));
            if (task.worktreePath() == null) throw new ConflictException("INTERACTION_WORKSPACE_MISSING", "Task workspace is unavailable");
            return new OpenCodeClient.OpenCodeSession(row.externalSessionId(), Path.of(task.worktreePath()));
        }
        DesignerSessionRow session = mapper.findDesignerSession(row.designerSessionId())
                .orElseThrow(() -> new NotFoundException("Designer session not found for interaction"));
        var project = mapper.findProject(session.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found for interaction"));
        return new OpenCodeClient.OpenCodeSession(row.externalSessionId(), Path.of(project.rootPath()));
    }

    private FeatureContracts.InteractionDto dto(InteractionRow row) {
        try {
            return new FeatureContracts.InteractionDto(row.id(), InteractionKind.valueOf(row.kind()),
                    InteractionState.valueOf(row.state()), row.taskId(), row.designerSessionId(),
                    row.localSessionId(), row.externalRequestId(), json.readTree(row.payloadJson()), row.version(),
                    row.resolvedAction() == null ? null : InteractionAction.valueOf(row.resolvedAction()),
                    row.createdAt(), row.updatedAt(), row.resolvedAt());
        } catch (JacksonException exception) {
            throw new BadRequestException("INTERACTION_PAYLOAD_INVALID", "Stored interaction payload is invalid");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new BadRequestException("INTERACTION_PAYLOAD_INVALID", exception.getMessage()); }
    }

    private String now() { return Instant.now().toString(); }
}
