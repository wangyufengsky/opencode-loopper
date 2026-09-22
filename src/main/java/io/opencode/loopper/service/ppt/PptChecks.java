package io.opencode.loopper.service.ppt;

import io.opencode.loopper.ppt.PptEngine;
import io.opencode.loopper.ppt.PptModel.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PptChecks {
    private final PptDocuments documents;
    private final PptResources resources;
    private final PptEngine engine;
    public PptChecks(PptDocuments documents,PptResources resources,PptEngine engine){this.documents=documents;this.resources=resources;this.engine=engine;}
    public Validation check(String document,Long revision) {
        var deck=documents.deck(document,revision);var issues=new ArrayList<>(engine.validate(deck).issues());
        Map<String,Boolean> verified=new HashMap<>();
        for(var slide:deck.slides())for(var element:slide.elements())if("image".equals(element.type())) {
            boolean present=verified.computeIfAbsent(element.assetId(),id->{try{resources.asset(document,id);return true;}catch(RuntimeException unavailable){return false;}});
            if(!present)issues.add(new Issue("ERROR","PPT_ASSET_UNAVAILABLE",slide.id(),element.id(),"图片素材缺失或哈希不符，请重新上传并替换该对象的素材引用"));
        }
        return new Validation(issues);
    }
}
