package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Owns immutable attachment bytes. Callers only persist returned relative references. */
@Component
public class DesignerAttachmentStore {
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    static final long MAX_CONTEXT_BYTES = 128L * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "csv", "xml", "yaml", "yml", "toml", "ini", "conf",
            "properties", "java", "kt", "kts", "groovy", "gradle", "js", "jsx", "ts", "tsx", "vue",
            "css", "scss", "less", "html", "htm", "py", "rb", "go", "rs", "c", "h", "cc", "cpp",
            "hpp", "cs", "sql", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "pem", "key",
            "crt", "cer", "log", "diff", "patch");
    private final Path dataRoot;
    private final Path root;
    private final ObjectMapper json;

    public DesignerAttachmentStore(LoopperProperties properties, ObjectMapper json) {
        this.dataRoot = properties.getDataDir().toAbsolutePath().normalize();
        this.root = dataRoot.resolve("design-attachments").normalize();
        this.json = json;
    }

    PreparedFile inspect(DesignerAttachmentContext.IncomingFile incoming) {
        String filename = safeFilename(incoming.filename());
        byte[] bytes = incoming.bytes();
        if (bytes.length == 0) throw bad("ATTACHMENT_EMPTY", filename + " 为空");
        if (bytes.length > MAX_FILE_BYTES) throw bad("ATTACHMENT_FILE_TOO_LARGE", filename + " 超过 20 MiB");
        String extension = extension(filename);
        if (Set.of("docx", "xlsx", "pptx").contains(extension)) return inspectOoxml(filename, extension, bytes);
        if ("pdf".equals(extension)) return inspectPdf(filename, bytes);
        if (Set.of("png", "jpg", "jpeg", "gif", "webp").contains(extension)) {
            return inspectImage(filename, extension, bytes);
        }
        if (archive(bytes) || executable(bytes)) {
            throw bad("ATTACHMENT_TYPE_UNSUPPORTED", filename + " 是压缩包或可执行文件，不能作为设计附件");
        }
        boolean text = TEXT_EXTENSIONS.contains(extension) || filename.equals(".env") || filename.startsWith(".env.");
        if (!text) throw bad("ATTACHMENT_TYPE_UNSUPPORTED", filename + " 的文件类型不在允许列表中");
        String decoded = strictUtf8(filename, bytes);
        if (bytes.length > MAX_CONTEXT_BYTES) {
            throw bad("ATTACHMENT_CONTEXT_TOO_LARGE", filename + " 的模型上下文超过 128 KiB，未做截断或摘要");
        }
        if ("json".equals(extension)) {
            try { json.readTree(decoded); }
            catch (RuntimeException failure) { throw bad("ATTACHMENT_PARSE_FAILED", filename + " 不是有效 JSON"); }
        }
        String mediaType = switch (extension) {
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "xml" -> "application/xml";
            default -> "text/plain";
        };
        return new PreparedFile(filename, mediaType, bytes.length, sha256(bytes), bytes,
                "UTF8_TEXT", "1", null, null, null, null, "TEXT");
    }

    StoredFile write(String designerSessionId, PreparedFile prepared) {
        String sessionSegment = safeSegment(designerSessionId);
        Path directory = root.resolve(sessionSegment).normalize();
        if (!directory.startsWith(root)) throw bad("ATTACHMENT_PATH_ESCAPE", "附件路径越过受管目录");
        String storedName = UUID.randomUUID() + ".bin";
        Path target = directory.resolve(storedName).normalize();
        Path temporary = directory.resolve(storedName + ".tmp").normalize();
        Path extractedTarget = prepared.extractedBytes() == null ? null
                : directory.resolve(storedName + ".context.txt").normalize();
        Path extractedTemporary = extractedTarget == null ? null
                : directory.resolve(storedName + ".context.txt.tmp").normalize();
        try {
            Files.createDirectories(directory);
            ownerOnly(directory, true);
            Files.write(temporary, prepared.bytes());
            ownerOnly(temporary, false);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            ownerOnly(target, false);
            if (extractedTarget != null) {
                Files.write(extractedTemporary, prepared.extractedBytes());
                ownerOnly(extractedTemporary, false);
                try { Files.move(extractedTemporary, extractedTarget, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(extractedTemporary, extractedTarget, StandardCopyOption.REPLACE_EXISTING);
                }
                ownerOnly(extractedTarget, false);
            }
            String relative = dataRoot.relativize(target).toString();
            String extractedRelative = extractedTarget == null ? null : dataRoot.relativize(extractedTarget).toString();
            return new StoredFile(prepared, relative, extractedRelative);
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            try { if (extractedTemporary != null) Files.deleteIfExists(extractedTemporary); } catch (IOException ignored) { }
            throw bad("ATTACHMENT_STORE_FAILED", "无法保存设计附件：" + safeMessage(failure));
        }
    }

    private PreparedFile inspectOoxml(String filename, String extension, byte[] bytes) {
        if (!archive(bytes)) throw bad("ATTACHMENT_MAGIC_MISMATCH", filename + " 不是有效 OOXML 容器");
        if (hasMacro(bytes)) throw bad("ATTACHMENT_MACRO_FORBIDDEN", filename + " 包含 Office 宏，不能作为设计附件");
        String extracted;
        String extractor;
        String mediaType;
        try {
            switch (extension) {
                case "docx" -> {
                    extractor = "OOXML_DOCX";
                    mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                        StringBuilder text = new StringBuilder();
                        document.getParagraphs().forEach(paragraph -> appendLine(text, paragraph.getText()));
                        document.getTables().forEach(table -> table.getRows().forEach(row ->
                                appendLine(text, row.getTableCells().stream().map(cell -> cell.getText()).toList())));
                        extracted = text.toString();
                    }
                }
                case "xlsx" -> {
                    extractor = "OOXML_XLSX";
                    mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                        DataFormatter formatter = new DataFormatter(Locale.ROOT);
                        StringBuilder text = new StringBuilder();
                        for (var sheet : workbook) {
                            appendLine(text, "# Sheet: " + sheet.getSheetName());
                            for (var row : sheet) {
                                List<String> values = new ArrayList<>();
                                for (var cell : row) values.add(formatter.formatCellValue(cell));
                                appendLine(text, values);
                            }
                        }
                        extracted = text.toString();
                    }
                }
                case "pptx" -> {
                    extractor = "OOXML_PPTX";
                    mediaType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
                        StringBuilder text = new StringBuilder();
                        int slide = 0;
                        for (var page : presentation.getSlides()) {
                            appendLine(text, "# Slide " + (++slide));
                            page.getShapes().stream().filter(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape)
                                    .map(shape -> ((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText())
                                    .forEach(value -> appendLine(text, value));
                        }
                        extracted = text.toString();
                    }
                }
                default -> throw new IllegalStateException("Unexpected OOXML extension");
            }
        } catch (BadRequestException failure) { throw failure; }
        catch (Exception failure) { throw bad("ATTACHMENT_PARSE_FAILED", filename + " 无法作为有效 OOXML 读取"); }
        byte[] representation = extracted.getBytes(StandardCharsets.UTF_8);
        if (representation.length > MAX_CONTEXT_BYTES) {
            throw bad("ATTACHMENT_CONTEXT_TOO_LARGE", filename + " 的确定性提取文本超过 128 KiB，未做截断或摘要");
        }
        return new PreparedFile(filename, mediaType, bytes.length, sha256(bytes), bytes, extractor, "1",
                "text/plain", (long) representation.length, sha256(representation), representation, "OFFICE");
    }

    private PreparedFile inspectPdf(String filename, byte[] bytes) {
        if (!starts(bytes, 0x25, 0x50, 0x44, 0x46, 0x2d)) {
            throw bad("ATTACHMENT_MAGIC_MISMATCH", filename + " 没有有效 PDF 文件头");
        }
        byte[] representation;
        try (var document = Loader.loadPDF(bytes)) {
            representation = new PDFTextStripper().getText(document).getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw bad("ATTACHMENT_PARSE_FAILED", filename + " 无法作为有效 PDF 读取");
        }
        if (representation.length > MAX_CONTEXT_BYTES) {
            throw bad("ATTACHMENT_CONTEXT_TOO_LARGE", filename + " 的确定性提取文本超过 128 KiB，未做截断或摘要");
        }
        return new PreparedFile(filename, "application/pdf", bytes.length, sha256(bytes), bytes,
                "PDF_TEXT", "1", "text/plain", (long) representation.length, sha256(representation),
                representation, "PDF");
    }

    private PreparedFile inspectImage(String filename, String extension, byte[] bytes) {
        String mediaType;
        boolean valid;
        switch (extension) {
            case "png" -> { mediaType = "image/png"; valid = starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a); }
            case "jpg", "jpeg" -> { mediaType = "image/jpeg"; valid = starts(bytes, 0xff, 0xd8, 0xff); }
            case "gif" -> { mediaType = "image/gif"; valid = ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a"); }
            case "webp" -> {
                mediaType = "image/webp";
                valid = ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP") && bytes.length >= 16
                        && (ascii(bytes, 12, "VP8 ") || ascii(bytes, 12, "VP8L") || ascii(bytes, 12, "VP8X"));
            }
            default -> throw new IllegalStateException("Unexpected image extension");
        }
        if (!valid) throw bad("ATTACHMENT_MAGIC_MISMATCH", filename + " 的扩展名与图片内容不匹配");
        if (!"webp".equals(extension)) {
            try {
                if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                    throw bad("ATTACHMENT_PARSE_FAILED", filename + " 无法作为有效图片解码");
                }
            } catch (IOException failure) { throw bad("ATTACHMENT_PARSE_FAILED", filename + " 无法作为有效图片解码"); }
        }
        return new PreparedFile(filename, mediaType, bytes.length, sha256(bytes), bytes,
                "RASTER_IMAGE", "1", null, null, null, null, "IMAGE");
    }

    private static boolean hasMacro(byte[] bytes) {
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = entries.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin") || name.contains("/activex/")) return true;
            }
            return false;
        } catch (IOException failure) { return true; }
    }
    private static void appendLine(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) target.append(value.strip()).append('\n');
    }
    private static void appendLine(StringBuilder target, List<String> values) {
        if (values.stream().anyMatch(value -> value != null && !value.isBlank())) {
            target.append(String.join("\t", values)).append('\n');
        }
    }

    Path resolve(String relativePath, String expectedSha256) {
        Path target = dataRoot.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new ConflictException("ATTACHMENT_CONTENT_MISSING", "冻结附件的受管文件不存在");
        }
        try {
            if (!sha256(Files.readAllBytes(target)).equals(expectedSha256)) {
                throw new ConflictException("ATTACHMENT_SHA_MISMATCH", "冻结附件的 SHA-256 与清单不一致");
            }
            return target;
        } catch (IOException failure) {
            throw new ConflictException("ATTACHMENT_READ_FAILED", "无法读取冻结附件");
        }
    }

    byte[] read(String relativePath, String expectedSha256) {
        Path target = resolve(relativePath, expectedSha256);
        try { return Files.readAllBytes(target); }
        catch (IOException failure) {
            throw new ConflictException("ATTACHMENT_READ_FAILED", "无法读取受管附件");
        }
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.contains("/") || value.contains("\\")) {
            throw bad("ATTACHMENT_FILENAME_INVALID", "附件文件名无效");
        }
        return value;
    }
    private static String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) throw bad("ATTACHMENT_PATH_ESCAPE", "附件会话标识无效");
        return value;
    }
    private static String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index <= 0 || index == filename.length() - 1 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }
    private static boolean archive(byte[] bytes) {
        return starts(bytes, 0x50, 0x4b, 0x03, 0x04) || starts(bytes, 0x50, 0x4b, 0x05, 0x06)
                || starts(bytes, 0x1f, 0x8b) || starts(bytes, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)
                || starts(bytes, 0x52, 0x61, 0x72, 0x21);
    }
    private static boolean executable(byte[] bytes) {
        return starts(bytes, 0x4d, 0x5a) || starts(bytes, 0x7f, 0x45, 0x4c, 0x46)
                || starts(bytes, 0xcf, 0xfa, 0xed, 0xfe) || starts(bytes, 0xca, 0xfe, 0xba, 0xbe);
    }
    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if ((bytes[index] & 0xff) != prefix[index]) return false;
        return true;
    }
    private static boolean ascii(byte[] bytes, int offset, String expected) {
        byte[] target = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || bytes.length < offset + target.length) return false;
        for (int index = 0; index < target.length; index++) if (bytes[offset + index] != target[index]) return false;
        return true;
    }
    private static String strictUtf8(String filename, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw bad("ATTACHMENT_UTF8_REQUIRED", filename + " 不是严格 UTF-8 文本");
        }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void ownerOnly(Path path, boolean directory) {
        try {
            Set<PosixFilePermission> permissions = directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) { }
    }
    private static BadRequestException bad(String code, String message) { return new BadRequestException(code, message); }
    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    record PreparedFile(String filename, String mediaType, long sizeBytes, String sha256, byte[] bytes,
                        String extractorId, String extractorVersion, String extractedMediaType,
                        Long extractedSizeBytes, String extractedSha256, byte[] extractedBytes,
                        String previewKind) {
        PreparedFile { bytes = bytes.clone(); extractedBytes = extractedBytes == null ? null : extractedBytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] extractedBytes() { return extractedBytes == null ? null : extractedBytes.clone(); }
    }
    record StoredFile(PreparedFile prepared, String relativePath, String extractedRelativePath) { }
}
