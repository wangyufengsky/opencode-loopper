package io.opencode.loopper.ppt;

import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static io.opencode.loopper.ppt.PptModel.*;

/** PPT capability facade. No persistence, sessions, file paths or workflow authority. */
@Component
public final class PptEngine {
    @FunctionalInterface
    public interface AssetResolver { byte[] read(String assetId) throws IOException; }

    private final PptOperations operations;
    private final PptLayoutChecks checks;
    private final PptRenderer renderer;
    private final PptFonts fonts;

    public PptEngine(ObjectMapper mapper) { this(mapper, ""); }
    @Autowired
    public PptEngine(ObjectMapper mapper, @Value("${loopper.ppt.font-dir:}") String fontDirectory) {
        this.fonts = new PptFonts(fontDirectory);
        this.operations = new PptOperations(mapper, fonts);
        this.checks = new PptLayoutChecks(fonts);
        this.renderer = new PptRenderer(fonts);
    }

    public OperationResult applyOperations(Deck deck, List<JsonNode> requested, boolean agent) {
        return operations.apply(deck, requested, agent);
    }
    public Validation validate(Deck deck) { return checks.validate(deck); }
    public TextMeasurement measureText(Deck deck, Element element) { return checks.measure(deck, element); }
    public byte[] renderPng(Deck deck, String slideId, double scale, AssetResolver assets) {
        return renderer.png(deck, slideId, scale, assets);
    }
    public byte[] exportPptx(Deck deck, AssetResolver assets) { return renderer.pptx(deck, assets); }
    public Capabilities capabilities() { return PptThemes.capabilities(fonts); }
}
