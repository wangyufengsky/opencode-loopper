package io.opencode.loopper.api;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.service.assist.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssistMcpServerTest {
    @Test void auxiliaryEndpointRequiresLoopbackAndGenerationBearerAndListsTheNineSchemas() throws Exception {
        var access=new InternalMcpRuntimeAccess();var credential=new InternalMcpCredentialProvider(()->19000).issue();access.activate(credential);
        var service=mock(AssistToolService.class);when(service.call(anyString(),anyMap())).thenReturn(new AssistToolService.Result(Map.of("code","ASSIST_SCOPE_DENIED","action","REAUTHORIZE"),true));
        try(var runtime=new AssistMcpServerConfiguration().assistMcpRuntime(service,new ObjectMapper(),"test")) {
            var mvc=MockMvcBuilders.routerFunctions(runtime.transport().getRouterFunction()).addFilters(new InternalMcpStreamableBearerFilter(access)).build();
            String initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            mvc.perform(post(AssistToolCatalog.ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(initialize)).andExpect(status().isUnauthorized());
            mvc.perform(post(AssistToolCatalog.ENDPOINT).with(r->{r.setRemoteAddr("10.0.0.1");return r;}).header("Authorization","Bearer "+credential.bearerToken()).contentType(MediaType.APPLICATION_JSON).content(initialize)).andExpect(status().isForbidden());
            var response=mvc.perform(post(AssistToolCatalog.ENDPOINT).header("Authorization","Bearer "+credential.bearerToken()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM).content(initialize)).andExpect(status().isOk()).andReturn();
            String session=response.getResponse().getHeader("Mcp-Session-Id");assertThat(session).isNotBlank();
            var listed=mvc.perform(post(AssistToolCatalog.ENDPOINT).header("Authorization","Bearer "+credential.bearerToken()).header("Mcp-Session-Id",session).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM).content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}" )).andReturn();
            if(listed.getRequest().isAsyncStarted())listed=mvc.perform(asyncDispatch(listed)).andReturn();
            String body=listed.getResponse().getContentAsString();for(var tool:AssistToolCatalog.tools())assertThat(body).contains(tool.name());
            assertThat(body).contains("scope").doesNotContain("submit_candidate",credential.bearerToken());
        }
    }
}
