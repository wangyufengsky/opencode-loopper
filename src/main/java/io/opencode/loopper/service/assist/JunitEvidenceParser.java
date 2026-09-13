package io.opencode.loopper.service.assist;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.helpers.DefaultHandler;

/** Diagnostic parser: counts testcase leaves once, preserves reruns, never establishes acceptance. */
public final class JunitEvidenceParser {
    public static final String VERSION="junit-evidence-1";
    public record Failure(int index,String name,String className,String state,String message,String stack,String output) { }
    public record Parsed(int tests,int failures,int errors,int skipped,boolean complete,String detail,List<Failure> cases) { }
    public static String decode(byte[] bytes) {
        try {
            var factory=javax.xml.stream.XMLInputFactory.newFactory();
            factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
            var reader=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            String encoding=reader.getEncoding();reader.close();
            var charset=java.nio.charset.Charset.forName(encoding==null?"UTF-8":encoding);
            String text=charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF","");
            return text.replaceFirst("(?i)(<\\?xml[^>]*encoding\\s*=\\s*['\"])[^'\"]+", "$1UTF-8");
        } catch(Exception invalid) { throw new AssistFailure("JUNIT_ENCODING_INVALID","报告编码或 XML 头不完整，无法安全解析"); }
    }
    public Parsed parse(String xml) {
        try {
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new DefaultHandler());
            var document=builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            if(!Set.of("testsuite","testsuites").contains(document.getDocumentElement().getTagName())) return invalid();
            var cases=document.getElementsByTagName("testcase");
            List<Failure> result=new ArrayList<>();int failed=0,errors=0,skipped=0,budget=200_000;
            boolean complete=cases.getLength()<=100_000;
            for(int i=0;i<Math.min(cases.getLength(),100_000);i++) {
                Element item=(Element)cases.item(i);String output=directText(item,"system-out")+directText(item,"system-err");
                boolean hasFailure=false,hasError=false;
                for(Node child=item.getFirstChild();child!=null;child=child.getNextSibling()) {
                    if(!(child instanceof Element element)) continue;
                    String tag=element.getTagName();if(tag.equals("skipped")) skipped++;
                    if(!Set.of("failure","error","rerunFailure","rerunError","flakyFailure","flakyError").contains(tag)) continue;
                    hasFailure|=tag.equals("failure");hasError|=tag.equals("error");
                    if(result.size()>=1000 || budget<=0) { complete=false;continue; }
                    String stack=clip(element.getTextContent(),Math.min(12000,budget));
                    if(stack.length()<element.getTextContent().length() || output.length()>2000)complete=false;budget-=stack.length();
                    String out=clip(output,Math.min(2000,Math.max(0,budget)));budget-=out.length();
                    result.add(new Failure(result.size(),clip(item.getAttribute("name"),256),clip(item.getAttribute("classname"),256),
                            tag,clip(element.getAttribute("message"),1000),stack,out));
                }
                if(hasFailure) failed++;if(hasError) errors++;
            }
            return new Parsed(cases.getLength(),failed,errors,skipped,complete,
                    cases.getLength()==0?"未发现测试用例，不能推断测试通过":complete?"报告解析完成；执行真实性和正式验收另行判断":"报告解析达到上限",List.copyOf(result));
        } catch(Exception failure) { return invalid(); }
    }
    private static String directText(Element element,String name) {
        StringBuilder result=new StringBuilder();
        for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element child && child.getTagName().equals(name)) result.append(clip(child.getTextContent(),2000));
        return clip(result.toString(),4000);
    }
    private static Parsed invalid() { return new Parsed(0,0,0,0,false,"报告损坏、不完整或格式不支持，不能推断测试通过",List.of()); }
    private static String clip(String value,int max) { return value.substring(0,Math.min(value.length(),max)); }
}
