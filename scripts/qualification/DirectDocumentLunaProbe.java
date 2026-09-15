import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Production analysis contracts over a closed effect fixture, not the HTTP/DB execution lifecycle. */
public class DirectDocumentLunaProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String FILE = "azx0-document", OWNER = "azx0-qualification";
    static String digest(String algorithm, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
    record Code(String path, String blobSha, String content, int lines) { }
    record Receipt(String path, int startLine, int endLine) { }
    record Submission(DirectDocumentAssessment.Candidate candidate, DirectDocumentAssessment.Review review,
                      List<Integer> sourceReads, List<Receipt> codeReads) { }
    public static void main(String[] args) throws Exception {
        var parsed = new AssistDocumentParser().parse("sample.docx", Files.readAllBytes(Path.of(args[1])));
        var sources = new ArrayList<DocumentRequirements.SourceSection>();
        for (var s : parsed.sections()) sources.add(new DocumentRequirements.SourceSection(FILE, Integer.parseInt(s.id()),
                s.title(), s.markdown(), digest("SHA-256", s.markdown().getBytes(StandardCharsets.UTF_8))));
        var code = new TreeMap<String, Code>();
        try (var paths = Files.list(Path.of(args[4]))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(path);
                var hash = MessageDigest.getInstance("SHA-1");
                hash.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                String content = new String(bytes, StandardCharsets.UTF_8);
                code.put(path.getFileName().toString(), new Code(path.getFileName().toString(), HexFormat.of().formatHex(hash.digest(bytes)),
                        content, content.split("\n", -1).length));
            }
        }
        String snapshot = digest("SHA-1", JSON.writeValueAsBytes(code));
        boolean review = args[2].equals("review");
        var kind = review ? MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2 : MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2;
        var candidate = review ? JSON.readValue(Files.readString(Path.of(args[3])), DirectDocumentAssessment.Candidate.class) : null;
        var input = new DocumentModelInput(sources.stream().map(s -> new DocumentModelInput.SectionRef(FILE, s.section(), s.sha256())).toList(),
                null, null, snapshot, null, null, List.of(), candidate, null, 1, 1);
        String id = "azx0-" + args[2];
        var row = new DocumentTemplateModelRow(id, OWNER, kind.name(), 0, 0, "RUNNING", JSON.writeValueAsString(input), "", null,
                "session-" + args[2], null, null, null, null, null, "", "", 0);
        if (args[0].equals("prepare")) {
            var data = new LinkedHashMap<String, Object>();
            data.put("runId", id); data.put("sources", sources); data.put("code", code.values()); data.put("snapshotSha", snapshot);
            data.put("limitations", parsed.limitations()); data.put("parserVersion", AssistDocumentParser.VERSION);
            data.put("prompt", new DocumentModelPrompt(JSON).build(row, "qualification"));
            data.put("toolName", InternalMcpContractCatalog.toolName(kind)); data.put("schema", InternalMcpContractCatalog.inputSchema(kind));
            data.put("assessment", candidate);
            System.out.println(JSON.writeValueAsString(data)); return;
        }
        try (var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                try {
                    var submission = JSON.readValue(scanner.nextLine(), Submission.class);
                    var documents = (DocumentTemplateMapper) Proxy.newProxyInstance(DirectDocumentLunaProbe.class.getClassLoader(),
                            new Class<?>[]{DocumentTemplateMapper.class}, (proxy, method, values) -> switch (method.getName()) {
                                case "sourceSections" -> sources.stream().filter(s -> OWNER.equals(values[0]) && (int) values[1] == 1
                                        && FILE.equals(values[2]) && s.section() >= (int) values[3]).limit((int) values[4])
                                        .map(s -> new DocumentTemplateMapper.Section(FILE, s.section(), s.title(), s.text(), s.sha256())).toList();
                                case "sourceRead" -> row.externalSessionId().equals(values[0]) && OWNER.equals(values[1]) && (int) values[2] == 1
                                        && FILE.equals(values[3]) && submission.sourceReads().contains((int) values[4])
                                        && sources.stream().anyMatch(s -> s.section() == (int) values[4] && s.sha256().equals(values[5]));
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
                    var codeMapper = (DocumentCodeMapper) Proxy.newProxyInstance(DirectDocumentLunaProbe.class.getClassLoader(),
                            new Class<?>[]{DocumentCodeMapper.class}, (proxy, method, values) -> {
                                if (method.getName().equals("file")) {
                                    var file = code.get(values[1]);
                                    return OWNER.equals(values[0]) && file != null ? Optional.of(new DocumentCodeMapper.File(OWNER, file.path(), file.blobSha(),
                                            "100644", file.content().getBytes(StandardCharsets.UTF_8).length, null)) : Optional.empty();
                                }
                                if (method.getName().equals("evidence")) {
                                    var file = code.get(values[1]); int start = (int) values[2], end = (int) values[3];
                                    var receipt = submission.codeReads().stream().filter(r -> r.path().equals(values[1]) && r.startLine() <= start && r.endLine() >= end).findFirst();
                                    if (!id.equals(values[0]) || file == null || receipt.isEmpty() || end > file.lines()) return Optional.empty();
                                    var r = receipt.get();
                                    String content = String.join("\n", Arrays.copyOfRange(file.content().split("\n", -1), r.startLine() - 1, r.endLine()));
                                    return Optional.of(new DocumentCodeMapper.Read(id, file.path(), file.blobSha(), r.startLine(), r.endLine(), content,
                                            digest("SHA-256", content.getBytes(StandardCharsets.UTF_8)), "qualification"));
                                }
                                throw new UnsupportedOperationException(method.getName());
                            });
                    var validation = new DirectDocumentAssessmentValidation(documents, new DocumentAssessmentValidation(codeMapper));
                    Object canonical = review ? validation.review(row, input, submission.review()) : validation.assessment(row, input, submission.candidate());
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "ACCEPTED", "canonicalCandidate", canonical)));
                } catch (BadRequestException failure) {
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "REJECTED", "detail", failure.getMessage(), "jsonPointer", failure instanceof DocumentCandidateProblem specific ? specific.pointer() : "/candidate")));
                } catch (RuntimeException failure) {
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "REJECTED", "detail", "候选 JSON 无法按生产合同读取")));
                }
            }
        }
    }
}
