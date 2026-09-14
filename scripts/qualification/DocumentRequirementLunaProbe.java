import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.service.DocumentModelPrompt;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import io.opencode.loopper.template.*;
import static io.opencode.loopper.template.DocumentRequirements.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Offline effect probe: production parser/prompt/schema/validator; no production lifecycle impersonation. */
public class DocumentRequirementLunaProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static void main(String[] args) throws Exception {
        var parsed = new AssistDocumentParser().parse("sample.docx", Files.readAllBytes(Path.of(args[1])));
        List<SourceSection> sources = new ArrayList<>();
        for (var s : parsed.sections()) sources.add(new SourceSection("azx0-document", Integer.parseInt(s.id()), s.title(), s.markdown(), sha(s.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        boolean review = args[2].equals("review");
        var kind = review ? MachineCandidateKind.DOCUMENT_REQUIREMENT_REVIEW_V1 : MachineCandidateKind.DOCUMENT_REQUIREMENTS_V1;
        Candidate candidate = review ? JSON.readValue(Files.readString(Path.of(args[3])), Candidate.class) : null;
        if (args[0].equals("prepare")) {
            var input = new DocumentModelInput(sources.stream().map(s -> new DocumentModelInput.SectionRef(s.fileId(), s.section(), s.sha256())).toList(), candidate, null, null, null, null);
            String runId = "azx0-" + args[2];
            var row = new DocumentTemplateModelRow(runId, "azx0-qualification", kind.name(), 0, 0, "RUNNING", JSON.writeValueAsString(input), "", null, null, null, null, null, null, null, "", "", 0);
            System.out.println(JSON.writeValueAsString(Map.of("runId", runId, "parserVersion", AssistDocumentParser.VERSION,
                    "sources", sources, "limitations", parsed.limitations(), "prompt", new DocumentModelPrompt(JSON).build(row, "qualification"),
                    "toolName", InternalMcpContractCatalog.toolName(kind), "schema", InternalMcpContractCatalog.inputSchema(kind))));
            return;
        }
        try (var scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                try {
                    if (review) DocumentRequirementValidation.review(sources, candidate, JSON.readValue(line, Review.class));
                    else DocumentRequirementValidation.extraction(sources, JSON.readValue(line, Candidate.class));
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "ACCEPTED")));
                } catch (BadRequestException failure) {
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "REJECTED", "detail", failure.getMessage())));
                } catch (RuntimeException failure) {
                    System.out.println(JSON.writeValueAsString(Map.of("outcome", "REJECTED", "detail", "候选 JSON 无法按生产合同读取")));
                }
            }
        }
    }
}
