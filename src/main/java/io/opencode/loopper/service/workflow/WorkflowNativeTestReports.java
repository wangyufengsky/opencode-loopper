package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.Result;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.template.SourceTestProfile;
import io.opencode.loopper.workflow.WorkflowNativeTest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.w3c.dom.*;

/** Bounded native report acquisition after proven process stop; never trusts console success text. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class WorkflowNativeTestReports {
    static final int FILE_LIMIT=256*1024,TOTAL_LIMIT=2*1024*1024,MAX_FILES=128;
    private final WorkflowNativeTestEvidence evidence;
    private final WorkflowEncoding encoding;
    private final WorkflowCommandWorkspace workspace;
    public WorkflowNativeTestReports(WorkflowNativeTestEvidence evidence,WorkflowEncoding encoding,WorkflowCommandWorkspace workspace){this.evidence=evidence;this.encoding=encoding;this.workspace=workspace;}
    public record Counts(int total,int passed,int failed,int skipped){
        Counts plus(Counts other){return new Counts(Math.addExact(total,other.total),Math.addExact(passed,other.passed),Math.addExact(failed,other.failed),Math.addExact(skipped,other.skipped));}
    }
    public record File(String path,String sha256,String content){ }
    public record Report(boolean valid,String message,Counts counts,List<File> files,Boolean inputUnchanged){
        public Report(boolean valid,String message,Counts counts,List<File> files){this(valid,message,counts,files,null);}
        public Report{files=List.copyOf(files);}
    }
    public void preflight(Path root,SourceTestProfile.Module module) {
        try {
            Path target=target(root,module);DurableCommandProtocol.check(target);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw stale();
            if(!Set.of("junit","testng").contains(module.framework())) {
                Files.createDirectories(target.getParent());DurableCommandProtocol.check(target.getParent());
            }
        }catch(IOException failure){throw stale();}
    }
    public void capture(WorkflowCommandStore.Context context,Path root,Result result) {
        String id=context.run().attemptId();var module=context.nativeTest().module();
        if(!result.stopConfirmed())throw stale();
        var saved=evidence.find(id);if(saved.isPresent()){evidence.report(id,result);return;}
        Report report=result.launched()?collect(root,module):new Report(false,"测试进程没有启动。",new Counts(0,0,0,0),List.of());
        var input=context.input();var ref=encoding.decode(encoding.encode(input.content()),io.opencode.loopper.workflow.WorkflowCodeSnapshot.Reference.class);
        boolean unchanged=workspace.unchanged(id,context.run().projectId(),context.run().requirementId(),input.attemptId(),ref,root);
        report=new Report(report.valid()&&unchanged,unchanged?report.message():"执行期间固定源码、测试或配置发生变化，本次报告不能证明原交付物通过测试。",report.counts(),report.files(),unchanged);
        evidence.save(id,result,report);
    }
    Report collect(Path root,SourceTestProfile.Module module) {
        var files=new ArrayList<File>();var counts=new Counts(0,0,0,0);int bytes=0;
        try {
            Path target=target(root,module);DurableCommandProtocol.check(target);
            var paths=new ArrayList<Path>();
            if(Set.of("junit","testng").contains(module.framework())) {
                if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))throw new IOException();
                try(var stream=Files.list(target)) {
                    var entries=stream.limit(1025).toList();if(entries.size()>1024)throw new IOException();
                    for(var file:entries)if(file.getFileName().toString().startsWith("TEST-")&&file.getFileName().toString().endsWith(".xml"))paths.add(file);
                }
            }else paths.add(target);
            if(paths.isEmpty()||paths.size()>MAX_FILES)throw new IOException();paths.sort(Comparator.naturalOrder());
            for(var path:paths) {
                DurableCommandProtocol.check(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException();
                byte[] body;try(var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){body=stream.readNBytes(FILE_LIMIT+1);}
                bytes+=body.length;if(body.length>FILE_LIMIT||bytes>TOTAL_LIMIT)throw new IOException();
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
                files.add(new File(root.relativize(path).toString().replace('\\','/'),ImmutableContentStore.hash(body),text));
                counts=counts.plus(module.framework().equals("jest")||module.framework().equals("vitest")?json(text):xml(text));
            }
            if(counts.total()>100000||counts.passed()+counts.failed()==0)return new Report(false,"没有实际执行的测试，不能把零测试或全部跳过算作通过。",counts,files);
            return new Report(true,"已读取本次原生测试报告。",counts,files);
        }catch(Exception invalid){return new Report(false,"原生测试报告缺失、不完整或无法校验，请查看命令日志并修复测试配置。",new Counts(0,0,0,0),files);}
    }
    private static Path target(Path root,SourceTestProfile.Module module) {
        Path path=root.resolve(WorkflowNativeTest.reportPath(module)).normalize();if(!path.startsWith(root)||path.equals(root))throw stale();return path;
    }
    static Counts xml(String text)throws Exception {
        var doc=xmlDocument(text);String root=doc.getDocumentElement().getTagName();
        if(!Set.of("testsuite","testsuites").contains(root))throw new IOException();
        var suites=doc.getElementsByTagName("testsuite");var total=new Counts(0,0,0,0);
        for(int i=0;i<suites.getLength();i++) {
            Element suite=(Element)suites.item(i);if(suite.getElementsByTagName("testsuite").getLength()>0)continue;
            int count=0,failed=0,skipped=0,errors=0;var children=suite.getChildNodes();
            for(int j=0;j<children.getLength();j++)if(children.item(j) instanceof Element test&&test.getTagName().equals("testcase")) {
                count++;boolean failure=test.getElementsByTagName("failure").getLength()>0,error=test.getElementsByTagName("error").getLength()>0,skip=test.getElementsByTagName("skipped").getLength()>0;
                if((failure?1:0)+(error?1:0)+(skip?1:0)>1)throw new IOException();if(failure)failed++;if(error)errors++;if(skip)skipped++;
            }
            if(number(suite,"tests")!=count||number(suite,"failures")!=failed||number(suite,"errors")!=errors||number(suite,"skipped")!=skipped)throw new IOException();
            total=total.plus(new Counts(count,count-failed-errors-skipped,failed+errors,skipped));
        }
        if(suites.getLength()==0)throw new IOException();return total;
    }
    Counts json(String text)throws IOException {
        var doc=encoding.decode(text,tools.jackson.databind.JsonNode.class);var suites=doc.path("testResults");
        if(!doc.isObject()||!suites.isArray()||!doc.path("success").isBoolean())throw new IOException();int total=0,passed=0,failed=0,skipped=0;
        for(var suite:suites) {
            if(!suite.path("assertionResults").isArray())throw new IOException();
            for(var test:suite.path("assertionResults")){total++;switch(test.path("status").asString("")){case "passed"->passed++;case "failed"->failed++;case "pending","todo","skipped","disabled"->skipped++;default->throw new IOException();}}
        }
        if(number(doc,"numTotalTests")!=total||number(doc,"numPassedTests")!=passed||number(doc,"numFailedTests")!=failed
                ||number(doc,"numPendingTests")+(doc.has("numTodoTests")?number(doc,"numTodoTests"):0)!=skipped||doc.path("success").asBoolean()!=(failed==0))throw new IOException();
        return new Counts(total,passed,failed,skipped);
    }
    static Document xmlDocument(String text)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
        return builder.parse(new org.xml.sax.InputSource(new StringReader(text)));
    }
    private static int number(Element element,String name)throws IOException {
        String value=element.getAttribute(name);if(value.isEmpty()&&!name.equals("tests"))return 0;
        if(!value.matches("[0-9]{1,6}"))throw new IOException();return Integer.parseInt(value);
    }
    private static int number(tools.jackson.databind.JsonNode node,String name)throws IOException {var value=node.path(name);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<0||value.asInt()>100000)throw new IOException();return value.asInt();}
    private static ConflictException stale(){return new ConflictException("WORKFLOW_TEST_REPORT_NOT_FRESH","测试报告位置已有内容或不可安全读取，请保留现场并重新准备测试节点；不能复用旧报告。");}
}
