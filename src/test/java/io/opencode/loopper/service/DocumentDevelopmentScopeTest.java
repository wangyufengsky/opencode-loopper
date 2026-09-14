package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class DocumentDevelopmentScopeTest {
    @TempDir Path root;
    private final AssistMapper sessions = mock(AssistMapper.class);
    private final DocumentTemplateMapper runs = mock(DocumentTemplateMapper.class);
    private final DocumentRequirementMapper requirements = mock(DocumentRequirementMapper.class);
    private final DocumentDevelopmentMapper bindings = mock(DocumentDevelopmentMapper.class);
    private final LoopperMapper domain = mock(LoopperMapper.class);
    private final InternalMcpRuntimeAccess runtime = new InternalMcpRuntimeAccess();
    private DocumentDevelopmentScope scopes;
    private final DocumentDevelopmentDirectory directories = mock(DocumentDevelopmentDirectory.class);
    private final AtomicReference<DocumentDevelopmentMapper.Scope> saved = new AtomicReference<>();
    @BeforeEach void prepare() {
        when(directories.root(any())).thenReturn(root.toString());
        var credentials = new InternalMcpCredentialProvider(() -> 18083).issue(); runtime.activate(credentials);
        scopes = new DocumentDevelopmentScope(sessions, runs, requirements, bindings, domain, runtime, new ObjectMapper(), directories);
        when(sessions.session("session")).thenReturn(new AssistMapper.Session("session", credentials.generation(), root.toString(),
                "PACKAGE_DESIGN_CANDIDATE_READ_ONLY", "[]", "{}", "now"));
        when(sessions.candidateOwner("session")).thenReturn(new AssistMapper.Owner("project", null, null, null, "designer", "OPEN"));
        when(runs.findDesigner("designer")).thenReturn(Optional.of(run(1, "DESIGNING")));
        when(domain.documentDesignerRevision("designer")).thenReturn(1);
        when(requirements.revision("run", 1)).thenReturn(Optional.of(new DocumentRequirementMapper.Revision("run", 1, "manifest-1", "[]", "now")));
        when(runs.revisionFiles("run", 1)).thenReturn(List.of(new DocumentTemplateFileRow("file", "run", 0, "需求.md", "MARKDOWN", 10,
                "original-hash", "representation", "v1", "run/file.original", 1, "[]")));
        when(domain.findProject("project")).thenReturn(Optional.of(new ProjectRow("project", "project", root.toString(), "", "now", "now", 1, 0)));
        when(bindings.scope("session")).thenAnswer(call -> Optional.ofNullable(saved.get()));
        when(bindings.bind(any())).thenAnswer(call -> { saved.compareAndSet(null, call.getArgument(0)); return 1; });
    }
    @Test void transportGrantIsBoundToFrozenVersionAndContainsNoPersistedCredential() {
        String token = token(); var original = scopes.authorize(token);
        assertThat(original.requirementRevision()).isEqualTo(1); assertThat(original.filesJson()).contains("original-hash");
        assertThat(original.toString()).doesNotContain(token);
        when(runs.findDesigner("designer")).thenReturn(Optional.of(run(2, "DESIGNING")));
        when(requirements.revision("run", 2)).thenReturn(Optional.of(new DocumentRequirementMapper.Revision("run", 2, "new", "[]", "later")));
        assertThat(scopes.authorize(token).requirementRevision()).isEqualTo(1);
        assertThat(token()).isEqualTo(token);
        verify(requirements, never()).revision("run", 2);
    }
    @Test void changedOwnerRuntimeOrManifestCannotReuseGrant() {
        String token = token();
        when(requirements.revision("run", 1)).thenReturn(Optional.of(new DocumentRequirementMapper.Revision("run", 1, "changed", "[]", "now")));
        assertThatThrownBy(() -> scopes.authorize(token)).isInstanceOf(ConflictException.class);
        when(requirements.revision("run", 1)).thenReturn(Optional.of(new DocumentRequirementMapper.Revision("run", 1, "manifest-1", "[]", "now")));
        when(sessions.candidateOwner("session")).thenReturn(new AssistMapper.Owner("other", null, null, null, "designer", "OPEN"));
        assertThatThrownBy(() -> scopes.authorize(token)).isInstanceOf(ConflictException.class);
        runtime.activate(new InternalMcpCredentialProvider(() -> 18083).issue());
        assertThatThrownBy(() -> scopes.authorize(token)).isInstanceOf(ConflictException.class);
    }
    @Test void newExecutionSessionKeepsItsStageSourceAfterLaterDocumentRevision() {
        var current = sessions.session("session");
        when(sessions.session("session")).thenReturn(new AssistMapper.Session("session", current.generation(), root.toString(),
                "IMPLEMENTATION", "[]", "{}", "now"));
        when(sessions.executionOwner("session")).thenReturn(new AssistMapper.Owner("project", "task", "stage", "attempt", null, "RUNNING"));
        when(runs.findTask("task")).thenReturn(Optional.of(run(2, "EXECUTING")));
        when(domain.documentStageRevision("stage")).thenReturn(1);
        assertThat(scopes.authorize(token()).requirementRevision()).isEqualTo(1);
        verify(requirements, never()).revision("run", 2);
        verify(runs, never()).revisionFiles("run", 2);
    }
    @Test void restartedSinglePackageSessionUsesTaskCreationSource() {
        var current = sessions.session("session");
        when(sessions.session("session")).thenReturn(new AssistMapper.Session("session", current.generation(), root.toString(),
                "IMPLEMENTATION", "[]", "{}", "now"));
        when(sessions.executionOwner("session")).thenReturn(new AssistMapper.Owner("project", "task", "stage", "attempt", null, "RUNNING"));
        when(runs.findTask("task")).thenReturn(Optional.of(run(2, "EXECUTING")));
        when(domain.documentStageRevision("stage")).thenReturn(null);
        when(domain.documentTaskRevision("task")).thenReturn(1);
        assertThat(scopes.authorize(token()).requirementRevision()).isEqualTo(1);
        verify(requirements, never()).revision("run", 2);
    }
    @Test void unrelatedOrdinarySessionGetsNoGrantAndForgedOrStoppedScopeIsDenied() {
        String token = token();
        assertThatThrownBy(() -> scopes.authorize(token.substring(0, token.length() - 3) + "xxx")).isInstanceOf(ConflictException.class);
        when(runs.findDesigner("designer")).thenReturn(Optional.of(run(1, "STOPPING")));
        assertThatThrownBy(() -> scopes.authorize(token)).isInstanceOf(ConflictException.class);
        when(runs.findDesigner("designer")).thenReturn(Optional.empty());
        var body = new HashMap<String, Object>(); scopes.enrich("session", body); assertThat(body).isEmpty();
    }
    private String token() {
        var body = new HashMap<String, Object>(); scopes.enrich("session", body);
        var match = Pattern.compile("scope=(lpd_[A-Za-z0-9_.-]+)").matcher((String) body.get("system"));
        assertThat(match.find()).isTrue(); return match.group(1);
    }
    private static DocumentTemplateRunRow run(int revision, String state) {
        return new DocumentTemplateRunRow("run", "request", "hash", "project", "REQUIREMENT_DEVELOPMENT", "1", "开发", state,
                null, null, null, "{}", "designer", null, revision, null, null, 0, "now", "now", 0);
    }
}
