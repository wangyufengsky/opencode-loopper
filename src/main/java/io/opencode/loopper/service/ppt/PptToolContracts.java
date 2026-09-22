package io.opencode.loopper.service.ppt;

import io.opencode.loopper.ppt.PptEngine;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Exact examples are deliberately versioned with the server operations they describe. */
final class PptToolContracts {
    private PptToolContracts() { }
    static Object capabilities(PptEngine engine,ObjectMapper json) {
        return Map.of("capabilities",engine.capabilities(),"contractVersion",2,"coordinates","point; top-left origin; default 960 × 540; all bounds numeric",
                "rules","IDs are stable and unique across the deck. Use explicit planned slide IDs; page order is not identity. Call measure/check before export. No silent text truncation. Writes require a new idempotencyKey and current expectedRevision. Same key may only replay identical input. In manual mode only the user confirms direction and production. With frozen generationAuthorization CREATE, the user has authorized automatic planning: submit a complete plan with your selectedDirectionId; the server validates and advances after this run ends. Read source sections before citing. Do not change locked objects.",
                "parameters",json.readTree(PARAMETERS),"operations",json.readTree(OPERATIONS),"element",json.readTree(ELEMENT),"plan",json.readTree(PLAN));
    }
    private static final String PARAMETERS="""
        {"ppt_get_context":{"slideId":"optional; omit for page/source index","revision":"optional snapshot number"},
         "ppt_read_source":{"sourceId":"resource ID","sectionId":"section index as string, e.g. 0","offset":0,"limit":12000},
         "ppt_submit_plan":{"idempotencyKey":"UUID","expectedRevision":0,"plan":"complete plan object below"},
         "ppt_apply_operations":{"idempotencyKey":"UUID","expectedRevision":0,"operations":"array of operations below"},
         "ppt_measure_text":{"revision":0,"element":"full element object; or provide slideId plus elementId"},
         "ppt_check_layout":{"revision":0},
         "ppt_render_preview":{"idempotencyKey":"UUID","revision":0,"slideId":"optional"},
         "ppt_get_job":{"jobId":"job ID"},"ppt_export":{"idempotencyKey":"UUID","revision":0}}
        """;
    private static final String OPERATIONS="""
        [{"op":"create_slide","slide":{"id":"slide-intro","title":"开场","section":"背景","notes":"演讲备注","elements":[]},"index":0,"clientRef":"intro"},
         {"op":"duplicate_slide","slideId":"slide-intro","index":1,"clientRef":"copied"},
         {"op":"move_slide","slideId":"slide-intro","index":0},
         {"op":"delete_slide","slideId":"slide-intro"},
         {"op":"update_slide","slideId":"slide-intro","patch":{"title":"新标题","section":"第一章","notes":"讲稿"}},
         {"op":"add_element","slideId":"slide-intro","element":{"id":"heading","type":"text","x":48,"y":40,"width":864,"height":60,"fontSize":32,"bold":true,"text":"主要结论"}},
         {"op":"update_element","slideId":"slide-intro","elementId":"heading","patch":{"text":"修改后的主要结论","x":60}},
         {"op":"remove_element","slideId":"slide-intro","elementId":"heading"},
         {"op":"move_element","slideId":"slide-intro","elementId":"heading","index":0},
         {"op":"apply_theme","theme":"business"},
         {"op":"apply_layout","slideId":"slide-intro","layout":"two_columns","elementIds":["a","b"]},
         {"op":"align","slideId":"slide-intro","elementIds":["a","b"],"alignment":"left"},
         {"op":"distribute","slideId":"slide-intro","elementIds":["a","b","c"],"axis":"horizontal"},
         {"op":"group","slideId":"slide-intro","elementIds":["a","b"],"groupId":"group-one"},
         {"op":"ungroup","slideId":"slide-intro","elementIds":["a","b"]}]
        """;
    private static final String ELEMENT="""
        {"required":["id","type","x","y","width","height"],"type":["text","image","shape","line","table","chart"],
         "fontSize":"8..144 points; default 22","fontFamily":"from capabilities.fonts","color":"RRGGBB or #RRGGBB",
         "fill":"RRGGBB; unset inherits theme","bold":false,"align":["left","center","right"],"bullets":false,
         "rotation":"-360..360; table and chart must be 0","allowOverlap":"only true for intentional decorative overlap",
         "stroke":"RRGGBB","lineWidth":"0..30","groupId":"optional stable group ID",
         "image":{"assetId":"uploaded image ID","fit":"contain or cover"},
         "shape":{"shape":"rect, roundRect, ellipse or arrow"},
         "table":{"rows":[["指标","本期"],["收入","100万元"]]},
         "chart":{"chart":{"type":"bar|line|pie","categories":["一季度","二季度"],"series":[{"name":"收入","values":[100,120],"color":"2563EB"}]}},
         "limits":"table 30 rows/12 columns; chart 30 categories/8 series; pie 1 series, nonnegative, positive total; text 10000 chars",
         "notes":"slide.notes holds speaker notes; chart and table text are editable in exported PPTX; never replace them with images"}
        """;
    private static final String PLAN="""
        {"brief":{"purpose":"制作目标","audience":"听众","duration":"10分钟","pageCount":12,"requirements":"约束与已有答案"},
         "directions":[{"id":"result","title":"结果与建议","description":"先给关键结果和建议，再解释原因","story":"结果→原因→行动","chapters":"结果、分析、建议","pageCount":12,"visual":"简洁商务"},
                       {"id":"problem","title":"问题与解决","description":"先展现需要解决的问题，再比较方案","story":"现状→问题→方案","chapters":"背景、问题、方案","pageCount":12,"visual":"深色数据"}],
         "selectedDirectionId":"real direction ID chosen by AI when generationAuthorization is CREATE; otherwise empty until user selects","theme":"business|minimal|dark",
         "narrative":{"story":"全稿主张、章节衔接和行动建议","chapters":[{"id":"chapter-one","title":"章节名称","purpose":"本章目的","pageCount":3}]},
         "slides":[{"id":"stable-slide-id","title":"页面标题","section":"章节","message":"本页要观众记住的一句话","content":"要点和依据","layout":"title_content",
                    "sourceIds":[],"visual":"图片/图表需求","notes":"讲稿"}],
         "visualRules":{"style":"简洁商务","fontFamily":"Noto Sans CJK SC","accentColor":"2563EB","density":"适中","aspectRatio":"16:9"},
         "assets":{"requirements":"素材需求","chartGuidance":"使用可编辑原生图表","imageGuidance":"使用用户上传且获准素材"},
         "delivery":{"fileName":"演示文稿.pptx","targetSoftware":"WPS / PowerPoint","includeNotes":true}}
        """;
}
