package io.opencode.loopper.service.assist;

import com.sun.net.httpserver.HttpServer;
import io.opencode.loopper.config.LoopperProperties;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class BatchAssistSafetyTest {
    @TempDir Path directory;
    @Test void junitCountsLeavesAndPreservesRerunsWithoutClaimingAcceptance() {
        var parsed=new JunitEvidenceParser().parse("<testsuites><testsuite tests='2'><testsuite tests='2'><testcase name='a'><failure message='expected'>stack</failure><rerunFailure>again</rerunFailure></testcase><testcase name='b'><skipped/></testcase></testsuite></testsuite></testsuites>");
        assertThat(parsed.tests()).isEqualTo(2);assertThat(parsed.failures()).isEqualTo(1);assertThat(parsed.skipped()).isEqualTo(1);
        assertThat(parsed.cases()).extracting(JunitEvidenceParser.Failure::state).containsExactly("failure","rerunFailure");
        assertThat(new JunitEvidenceParser().parse("<testsuite tests='2'/>").detail()).contains("不能推断");
        assertThat(new JunitEvidenceParser().parse("<testsuite><testcase>").complete()).isFalse();
        assertThat(new JunitEvidenceParser().parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite>&x;</testsuite>").complete()).isFalse();
    }
    @Test void declaredXmlEncodingIsNormalizedBeforeSnapshotParsing() {
        String source="<?xml version='1.0' encoding='UTF-16'?><testsuite><testcase name='中文'><failure>断言失败</failure></testcase></testsuite>";
        String normalized=JunitEvidenceParser.decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_16));
        var parsed=new JunitEvidenceParser().parse(normalized);
        assertThat(parsed.cases()).singleElement().satisfies(item->assertThat(item.name()).isEqualTo("中文"));
        assertThat(parsed.complete()).isTrue();
    }
    @Test void scannerRejectsEscapeAndDoesNotFollowSymlinks() throws Exception {
        Path logs=Files.createDirectories(directory.resolve("logs"));Files.writeString(logs.resolve("app.log"),"normal");
        Path outside=Files.createDirectories(directory.resolve("outside"));Files.writeString(outside.resolve("hidden.log"),"private");
        try { Files.createSymbolicLink(logs.resolve("link"),outside); }catch(UnsupportedOperationException ignored){ }
        var scan=EvidenceSourceScanner.scan(logs,List.of(new BatchAssistConfigService.Source("LOG","","**")));
        assertThat(scan.files()).hasSize(1);assertThat(scan.files().getFirst().path()).endsWith(Path.of("app.log"));
        assertThatThrownBy(()->EvidenceSourceScanner.externalRoot("/")).isInstanceOf(AssistFailure.class);
        assertThatThrownBy(()->AssistFiles.resolve(logs,"../outside/hidden.log")).isInstanceOf(AssistFailure.class);
    }
    @Test void gitlabTransferHasBoundedBodyAndRejectsRedirectsWithoutForwardingToken() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            List<String> seen=Collections.synchronizedList(new ArrayList<>());
            server.createContext("/api/v4/projects/", exchange -> {
                seen.add(exchange.getRequestURI().toString());
                if(exchange.getRequestURI().getPath().endsWith("/redirect")) {
                    exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/steal");exchange.sendResponseHeaders(302,-1);
                } else {byte[] body="x".repeat(GitLabReadTransport.LIMIT+4000).getBytes();exchange.sendResponseHeaders(200,body.length);try{exchange.getResponseBody().write(body);}catch(java.io.IOException ignored){} }
                exchange.close();
            });
            server.createContext("/steal", exchange->{seen.add("leaked");exchange.sendResponseHeaders(200,-1);exchange.close();});
            server.start();
            try {
                var props=new LoopperProperties();var config=props.getPublication().getGitlab();
                config.setHost("127.0.0.1");config.setApiBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v4"));config.setPrivateToken("synthetic-token");
                config.setRequestTimeout(Duration.ofSeconds(3));
                var transport=new GitLabReadTransport(props);
                var large=transport.get(transport.instance(),"/projects/1/trace");assertThat(large.truncated()).isTrue();assertThat(large.body()).hasSize(GitLabReadTransport.LIMIT);
                var redirected=transport.get(transport.instance(),"/projects/1/redirect");assertThat(redirected.status()).isEqualTo(302);assertThat(seen).doesNotContain("leaked");
                assertThatThrownBy(()->transport.get("http://evil.invalid/api/v4","/projects/1")).isInstanceOf(AssistFailure.class);
            } finally {server.stop(0);}
        }
    }
    @Test void newToolsAreExplicitAndReviewersCannotUseLiveGitlab() {
        assertThat(AssistToolCatalog.tools()).hasSize(31);
        assertThat(AssistToolCatalog.allowed("JUDGE")).contains("search_evidence","list_test_failures","read_test_failure").noneMatch(name->name.startsWith("gitlab_") || AssistToolCatalog.knowledgeTool(name));
    }
}
