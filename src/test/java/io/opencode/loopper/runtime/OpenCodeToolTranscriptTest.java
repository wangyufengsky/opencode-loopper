package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class OpenCodeToolTranscriptTest {
    @Test void rejectedToolShowsItsReasonBeforeBoundedArgumentsAndRedactsTheScope() {
        var messages=JsonNodeFactory.instance.arrayNode();var message=messages.addObject();
        message.putObject("info").put("role","assistant");
        var tool=message.putArray("parts").addObject().put("id","rejected").put("type","tool").put("tool","submit_workflow_node_result");
        String credential="lpw_fixture."+"x".repeat(43);
        var state=tool.putObject("state").put("status","error").put("error","请改为节点声明的交付类型；scope="+credential);
        state.putObject("input").put("content","a".repeat(45000));
        var transcript=new OpenCodeResponseParser().transcript(messages);
        assertThat(transcript.parts()).singleElement().satisfies(part->{
            assertThat(part.status()).isEqualTo("error");
            assertThat(part.content()).startsWith("工具错误\n请改为节点声明的交付类型")
                    .contains("[辅助作用域凭证已隐藏]","参数","output truncated by Loopper")
                    .doesNotContain(credential);
            assertThat(part.content().length()).isLessThan(40100);
        });
    }

    @Test void legacyToolErrorRetainsStructuredDetailAndPartialOutput() {
        var messages=JsonNodeFactory.instance.arrayNode();var message=messages.addObject();
        message.putObject("info").put("role","assistant");
        var tool=message.putArray("parts").addObject().put("type","tool-call").put("name","read").put("status","error");
        tool.putObject("error").put("message","文件已变化，请重新读取");tool.put("output","已读取的部分内容");
        assertThat(new OpenCodeResponseParser().transcript(messages).parts()).singleElement().satisfies(part->{
            assertThat(part.status()).isEqualTo("error");
            assertThat(part.content()).contains("工具错误","文件已变化，请重新读取","已读取的部分内容");
        });
    }
}
