package io.opencode.loopper.verification;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class HttpStatusVerifier implements NativeVerifierHandler {
    @Override public String type() { return "HTTP_STATUS"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        HttpEvidence response = HttpEvidence.fetch(spec, context.timeout());
        boolean passed = response.status == spec.expectedStatus();
        Map<String, Object> evidence = response.evidence();
        evidence.put("expectedStatus", spec.expectedStatus());
        return NativeVerifierHandlers.outcome(type(), passed, passed ? "HTTP status matched" : "Expected HTTP " + spec.expectedStatus() + " but received " + response.status, evidence);
    }
}

final class JsonPathVerifier implements NativeVerifierHandler {
    @Override public String type() { return "JSON_PATH"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        HttpEvidence response = HttpEvidence.fetch(spec, context.timeout());
        if (response.status < 200 || response.status >= 300) {
            return NativeVerifierHandlers.outcome(type(), false, "JSON endpoint returned HTTP " + response.status, response.evidence());
        }
        String observed = NativeVerifierHandlers.jsonPath(response.body, spec.jsonPath());
        boolean passed = NativeVerifierHandlers.matches(observed, spec.expectedValue(), spec.matchMode());
        Map<String, Object> evidence = response.evidence();
        evidence.put("jsonPath", spec.jsonPath()); evidence.put("observedValue", observed);
        evidence.put("expectedValue", spec.expectedValue()); evidence.put("matchMode", NativeVerifierHandlers.matchMode(spec));
        return NativeVerifierHandlers.outcome(type(), passed, passed ? "JSON path matched" : "JSON path did not match expected value", evidence);
    }
}

final class FileContentVerifier implements NativeVerifierHandler {
    @Override public String type() { return "FILE_CONTENT"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path file = VerifierSafety.managedRelative(context.worktree(), spec.path());
        byte[] bytes = NativeVerifierHandlers.readBounded(file, 1_000_000, "FILE_CONTENT");
        String observed = new String(bytes, StandardCharsets.UTF_8);
        boolean passed = NativeVerifierHandlers.matches(observed, spec.expectedContent(), spec.matchMode());
        Map<String, Object> evidence = NativeVerifierHandlers.fileEvidence(file, context.worktree(), bytes);
        evidence.put("matchMode", NativeVerifierHandlers.matchMode(spec)); evidence.put("expectedContent", spec.expectedContent());
        return NativeVerifierHandlers.outcome(type(), passed, passed ? "File content matched" : "File content did not match expected content", evidence);
    }
}

final class FileHashVerifier implements NativeVerifierHandler {
    @Override public String type() { return "FILE_HASH"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path file = VerifierSafety.managedRelative(context.worktree(), spec.path());
        byte[] bytes = NativeVerifierHandlers.readBounded(file, 20_000_000, "FILE_HASH");
        String actual = BinaryArtifactStore.sha256(bytes);
        boolean passed = actual.equalsIgnoreCase(spec.expectedSha256());
        Map<String, Object> evidence = NativeVerifierHandlers.fileEvidence(file, context.worktree(), bytes);
        evidence.put("expectedSha256", spec.expectedSha256());
        return NativeVerifierHandlers.outcome(type(), passed, passed ? "File SHA-256 matched" : "File SHA-256 did not match", evidence);
    }
}

final class JunitXmlVerifier implements NativeVerifierHandler {
    @Override public String type() { return "JUNIT_XML"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path file = VerifierSafety.managedRelative(context.worktree(), spec.path());
        byte[] bytes = NativeVerifierHandlers.readBounded(file, 4_000_000, "JUNIT_XML");
        String source = new String(bytes, StandardCharsets.UTF_8);
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) throw new TaskFailure("JUNIT_XML_XXE_FORBIDDEN", "JUnit XML may not contain DTD or entities");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            NodeList suites = document.getElementsByTagName("testsuite");
            long tests = 0, failures = 0, errors = 0, skipped = 0;
            for (int i = 0; i < suites.getLength(); i++) {
                Element suite = (Element) suites.item(i);
                tests += NativeVerifierHandlers.longAttribute(suite, "tests");
                failures += NativeVerifierHandlers.longAttribute(suite, "failures");
                errors += NativeVerifierHandlers.longAttribute(suite, "errors");
                skipped += NativeVerifierHandlers.longAttribute(suite, "skipped");
            }
            boolean passed = failures == 0 && errors == 0;
            Map<String, Object> evidence = NativeVerifierHandlers.fileEvidence(file, context.worktree(), bytes);
            evidence.put("suiteCount", suites.getLength()); evidence.put("tests", tests); evidence.put("failures", failures);
            evidence.put("errors", errors); evidence.put("skipped", skipped);
            return NativeVerifierHandlers.outcome(type(), passed, passed ? "JUnit XML reported no failures" : "JUnit XML reported failures or errors", evidence);
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception invalid) { throw new TaskFailure("JUNIT_XML_INVALID", "JUnit XML could not be parsed safely: " + invalid.getMessage()); }
    }
}

final class BrowserVerifier implements NativeVerifierHandler {
    private static final String EXTERNAL_NETWORK_POLICY = "DEAD_LOOPBACK_PROXY_AND_HOST_RESOLVER_DENY";
    @Override public String type() { return "BROWSER"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        URI initial = VerifierSafety.requireLoopbackHttp(spec.url());
        Path chrome = BrowserExecutableLocator.resolve();
        List<Map<String, Object>> observed = new ArrayList<>();
        Path trace = context.artifacts().reserve("application/zip");
        // The product deliberately uses the operator-installed browser. Prevent Playwright from
        // downloading a second platform-specific browser during first use of the packaged JAR.
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setExecutablePath(chrome).setHeadless(true).setArgs(List.of(
                        "--disable-extensions", "--no-first-run", "--no-default-browser-check",
                        "--disable-background-networking", "--disable-component-update", "--disable-domain-reliability",
                        "--disable-quic", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                        // All non-loopback HTTP, Service Worker, WebSocket and direct-IP traffic is sent
                        // to a local dead endpoint. The resolver rule is a second fail-closed layer.
                        "--proxy-server=http://127.0.0.1:9",
                        "--proxy-bypass-list=<-loopback>;localhost;*.localhost;127.0.0.1;[::1]",
                        "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE *.localhost, EXCLUDE 127.0.0.1, EXCLUDE [::1]")))) {
            BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions()
                    .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK));
            try {
                browserContext.route("**/*", route -> {
                    try { VerifierSafety.requireLoopbackHttp(route.request().url()); route.resume(); }
                    catch (TaskFailure blocked) { route.abort(); }
                });
                browserContext.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
                Page page = browserContext.newPage();
                page.navigate(initial.toString(), new Page.NavigateOptions().setTimeout((double) context.timeout().toMillis()).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                VerifierSafety.requireLoopbackHttp(page.url());
                boolean passed = true;
                for (LoopSpec.BrowserAssertion assertion : spec.assertions()) {
                    VerifierSafety.requireCssSelector(assertion.selector());
                    Locator locator = page.locator(assertion.selector());
                    long count = locator.count();
                    boolean current = switch (assertion.type()) {
                        case "EXISTS" -> count > 0;
                        case "VISIBLE" -> count > 0 && locator.first().isVisible();
                        case "TEXT_CONTAINS" -> count > 0 && String.valueOf(locator.first().textContent()).contains(assertion.value());
                        case "COUNT" -> count == (assertion.expectedCount() == null ? 1 : assertion.expectedCount());
                        case "ATTRIBUTE_EQUALS" -> count > 0 && assertion.attribute() != null && assertion.value() != null
                                && assertion.value().equals(locator.first().getAttribute(assertion.attribute()));
                        default -> throw new TaskFailure("BROWSER_ASSERTION_INVALID", "Unsupported BROWSER assertion type: " + assertion.type());
                    };
                    observed.add(Map.of("type", assertion.type(), "selector", assertion.selector(), "count", count, "passed", current));
                    passed &= current;
                }
                BinaryArtifactStore.ArtifactReference screenshot = context.artifacts().write("BROWSER_SCREENSHOT", "image/png",
                        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)), Map.of("url", page.url()));
                browserContext.tracing().stop(new Tracing.StopOptions().setPath(trace));
                BinaryArtifactStore.ArtifactReference traceArtifact = context.artifacts().finalizeReserved("BROWSER_TRACE", "application/zip", trace, Map.of("url", page.url()));
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("url", page.url()); evidence.put("assertions", observed);
                evidence.put("artifacts", List.of(screenshot.evidence(), traceArtifact.evidence()));
                evidence.put("chromeExecutable", chrome.toString()); evidence.put("loopbackOnly", true);
                evidence.put("serviceWorkers", "BLOCK"); evidence.put("externalNetworkPolicy", EXTERNAL_NETWORK_POLICY);
                return NativeVerifierHandlers.outcome(type(), passed, passed ? "Browser assertions matched" : "One or more browser assertions failed", evidence);
            } finally { browserContext.close(); }
        } catch (TaskFailure failure) { throw failure; }
        catch (RuntimeException browserFailure) { throw new TaskFailure("BROWSER_VERIFICATION_FAILED", "Browser verifier failed: " + browserFailure.getMessage()); }
    }
}

/** Resolves an operator-installed Chrome/Chromium without assuming a specific desktop OS. */
final class BrowserExecutableLocator {
    private static final String OVERRIDE = "LOOPPER_CHROME_EXECUTABLE";

    private BrowserExecutableLocator() { }

    static Path resolve() {
        return resolve(System.getProperty("os.name", ""), System.getenv());
    }

    static Path resolve(String osName, Map<String, String> environment) {
        String configured = environment.getOrDefault(OVERRIDE, "").trim();
        if (!configured.isEmpty()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (usable(candidate, osName)) return candidate;
            throw unavailable("Configured " + OVERRIDE + " is not an executable file: " + candidate);
        }

        List<Path> candidates = new ArrayList<>();
        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac")) {
            candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else if (normalizedOs.contains("win")) {
            addWindowsCandidates(candidates, environment.get("PROGRAMFILES"));
            addWindowsCandidates(candidates, environment.get("PROGRAMFILES(X86)"));
            String localAppData = environment.get("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Path.of(localAppData, "Google", "Chrome", "Application", "chrome.exe"));
                candidates.add(Path.of(localAppData, "Chromium", "Application", "chrome.exe"));
            }
        } else {
            candidates.addAll(List.of(
                    Path.of("/usr/bin/google-chrome"),
                    Path.of("/usr/bin/google-chrome-stable"),
                    Path.of("/usr/bin/chromium"),
                    Path.of("/usr/bin/chromium-browser"),
                    Path.of("/snap/bin/chromium"),
                    Path.of("/var/lib/flatpak/exports/bin/com.google.Chrome"),
                    Path.of("/var/lib/flatpak/exports/bin/org.chromium.Chromium")));
        }

        // PATH is an operator-controlled discovery boundary and must remain deterministic
        // even on hosts that also preinstall Chrome in a standard absolute location.
        for (String directory : environment.getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) continue;
            for (String executable : executableNames(normalizedOs)) {
                Path candidate = Path.of(directory, executable);
                if (usable(candidate, osName)) return candidate.toAbsolutePath().normalize();
            }
        }
        for (Path candidate : candidates) {
            if (usable(candidate, osName)) return candidate.toAbsolutePath().normalize();
        }
        throw unavailable("Chrome/Chromium was not found for " + osName);
    }

    private static void addWindowsCandidates(List<Path> candidates, String root) {
        if (root == null || root.isBlank()) return;
        candidates.add(Path.of(root, "Google", "Chrome", "Application", "chrome.exe"));
        candidates.add(Path.of(root, "Chromium", "Application", "chrome.exe"));
    }

    private static List<String> executableNames(String osName) {
        if (osName.contains("win")) return List.of("chrome.exe", "chromium.exe");
        if (osName.contains("mac")) return List.of("google-chrome", "chromium", "chrome");
        return List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser");
    }

    private static boolean usable(Path candidate, String osName) {
        return Files.isRegularFile(candidate)
                && (Files.isExecutable(candidate) || osName.toLowerCase(Locale.ROOT).contains("win"));
    }

    private static TaskFailure unavailable(String detail) {
        return new TaskFailure("BROWSER_CHROME_UNAVAILABLE",
                detail + ". Install Google Chrome/Chromium or set " + OVERRIDE + " to its executable path");
    }
}

final class DatabaseQueryVerifier implements NativeVerifierHandler {
    @Override public String type() { return "DATABASE_QUERY"; }
    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path database = VerifierSafety.managedRelative(context.worktree(), spec.path());
        if (!Files.isRegularFile(database)) throw new TaskFailure("DATABASE_FILE_INVALID", "DATABASE_QUERY requires an existing local SQLite file");
        NativeVerifierHandlers.requireReadonlySql(spec.sql());
        String jdbc = "jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(jdbc); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout((int) Math.max(1, context.timeout().toSeconds())); statement.setMaxRows(101);
            try (ResultSet rows = statement.executeQuery(spec.sql())) {
                ResultSetMetaData metadata = rows.getMetaData();
                int columns = metadata.getColumnCount();
                if (columns > 32) throw new TaskFailure("DATABASE_RESULT_LIMIT", "DATABASE_QUERY returned too many columns");
                List<String> names = new ArrayList<>();
                for (int column = 1; column <= columns; column++) names.add(metadata.getColumnLabel(column));
                int count = 0;
                while (rows.next()) { if (++count > 100) throw new TaskFailure("DATABASE_RESULT_LIMIT", "DATABASE_QUERY returned too many rows"); }
                boolean passed = spec.expectedRowCount() == null || count == spec.expectedRowCount();
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("databasePath", context.worktree().relativize(database).toString()); evidence.put("columns", names);
                evidence.put("rowCount", count); evidence.put("expectedRowCount", spec.expectedRowCount()); evidence.put("readOnly", true);
                return NativeVerifierHandlers.outcome(type(), passed, passed ? "SQLite query matched" : "SQLite row count did not match", evidence);
            }
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failed) { throw new TaskFailure("DATABASE_QUERY_FAILED", "DATABASE_QUERY failed: " + failed.getMessage()); }
    }
}

final class HttpEvidence {
    private static final int MAX_BODY_BYTES = 1_000_000;
    final int status; final byte[] body; final URI uri; final String method;
    private HttpEvidence(int status, byte[] body, URI uri, String method) { this.status = status; this.body = body; this.uri = uri; this.method = method; }
    static HttpEvidence fetch(VerifierSpec spec, Duration timeout) {
        URI uri = VerifierSafety.requireLoopbackHttp(spec.url());
        String method = spec.httpMethod() == null ? "GET" : spec.httpMethod();
        if (!("GET".equals(method) || "HEAD".equals(method))) throw new TaskFailure("HTTP_METHOD_FORBIDDEN", "HTTP and JSON verifiers allow GET or HEAD only");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).method(method, HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<InputStream> response = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                    .build().send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) { return new HttpEvidence(response.statusCode(), NativeVerifierHandlers.readBounded(body, MAX_BODY_BYTES, "HTTP_BODY"), uri, method); }
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failed) { throw new TaskFailure("HTTP_REQUEST_FAILED", "Loopback HTTP verifier failed: " + failed.getMessage()); }
    }
    Map<String, Object> evidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("url", uri.toString()); evidence.put("httpMethod", method); evidence.put("actualStatus", status);
        evidence.put("bodyBytes", body.length); evidence.put("bodySha256", BinaryArtifactStore.sha256(body)); evidence.put("loopbackOnly", true);
        return evidence;
    }
}

final class NativeVerifierHandlers {
    private NativeVerifierHandlers() { }
    static VerifierOutcome outcome(String type, boolean passed, String summary, Map<String, Object> evidence) {
        evidence.put("schemaVersion", "v1");
        return new VerifierOutcome(type, passed ? VerificationState.PASS : VerificationState.FAIL, summary, evidence);
    }
    static byte[] readBounded(Path path, int max, String type) {
        try (InputStream input = Files.newInputStream(path)) { return readBounded(input, max, type); }
        catch (IOException unavailable) { throw new TaskFailure("VERIFIER_FILE_UNREADABLE", type + " could not read its file: " + unavailable.getMessage()); }
    }
    static byte[] readBounded(InputStream input, int max, String type) throws IOException {
        byte[] result = input.readNBytes(max + 1);
        if (result.length > max) throw new TaskFailure("VERIFIER_EVIDENCE_LIMIT", type + " exceeded its safe size limit");
        return result;
    }
    static String relative(Path worktree, Path file) {
        try { return worktree.toRealPath().relativize(file.toRealPath()).toString().replace('\\', '/'); }
        catch (IOException failure) { throw new TaskFailure("VERIFIER_PATH_INVALID", "Verifier path could not be canonicalized"); }
    }
    static Map<String, Object> fileEvidence(Path file, Path worktree, byte[] bytes) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("path", worktree.relativize(file).toString()); evidence.put("sizeBytes", bytes.length);
        evidence.put("sha256", BinaryArtifactStore.sha256(bytes)); return evidence;
    }
    static String matchMode(VerifierSpec spec) { return spec.matchMode() == null ? "EXACT" : spec.matchMode(); }
    static boolean matches(String actual, String expected, String mode) {
        String normalized = mode == null ? "EXACT" : mode.toUpperCase(Locale.ROOT);
        if (expected == null) return false;
        return switch (normalized) {
            case "EXACT" -> expected.equals(actual);
            case "CONTAINS" -> actual.contains(expected);
            default -> throw new TaskFailure("VERIFIER_MATCH_MODE_INVALID", "Only EXACT and CONTAINS match modes are supported");
        };
    }
    static String jsonPath(byte[] source, String expression) {
        String path = expression == null ? "" : expression.trim();
        if (!path.matches("\\$([.][A-Za-z_][A-Za-z0-9_]*)*(\\[[0-9]{1,6}])*")) {
            throw new TaskFailure("JSON_PATH_INVALID", "JSON_PATH supports only $.field and [index] segments");
        }
        try {
            tools.jackson.databind.JsonNode current = new tools.jackson.databind.ObjectMapper().readTree(source);
            int index = 1;
            while (index < path.length()) {
                if (path.charAt(index) == '.') {
                    int end = index + 1;
                    while (end < path.length() && Character.isLetterOrDigit(path.charAt(end)) || (end < path.length() && path.charAt(end) == '_')) end++;
                    current = current.path(path.substring(index + 1, end)); index = end;
                } else {
                    int close = path.indexOf(']', index); current = current.path(Integer.parseInt(path.substring(index + 1, close))); index = close + 1;
                }
            }
            if (current.isMissingNode() || current.isNull()) return "";
            return current.isValueNode() ? current.asText() : current.toString();
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception invalid) { throw new TaskFailure("JSON_INVALID", "Loopback response was not valid JSON: " + invalid.getMessage()); }
    }
    static long longAttribute(Element element, String name) {
        try { return Long.parseLong(element.getAttribute(name).isBlank() ? "0" : element.getAttribute(name)); }
        catch (NumberFormatException invalid) { throw new TaskFailure("JUNIT_XML_INVALID", "JUnit XML has an invalid " + name + " count"); }
    }
    static void requireReadonlySql(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (normalized.isBlank() || normalized.contains(";") || normalized.contains("--") || normalized.contains("/*")) {
            throw new TaskFailure("DATABASE_SQL_FORBIDDEN", "DATABASE_QUERY requires one comment-free SQL statement");
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH")) || upper.matches(".*\\b(INSERT|UPDATE|DELETE|REPLACE|CREATE|ALTER|DROP|PRAGMA|ATTACH|DETACH|VACUUM|REINDEX|ANALYZE)\\b.*")) {
            throw new TaskFailure("DATABASE_SQL_FORBIDDEN", "DATABASE_QUERY allows only a read-only SELECT/WITH statement");
        }
    }
}
