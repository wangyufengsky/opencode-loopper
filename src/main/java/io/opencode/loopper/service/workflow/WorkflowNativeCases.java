package io.opencode.loopper.service.workflow;

import io.opencode.loopper.template.SourceTestProfile;
import java.nio.file.Path;
import java.util.*;
import org.w3c.dom.Element;

/** Deterministic case identities from the already-frozen native report bytes, never console output. */
final class WorkflowNativeCases {
    private WorkflowNativeCases(){ }
    record Case(String id,String reportPath,String suite,String name,String status,String testPath){ }
    static List<Case> read(WorkflowNativeTestReports.Report report,SourceTestProfile.Module module,String directory,WorkflowEncoding encoding) {
        var result=new ArrayList<Case>();
        try {
            for(var file:report.files()) {
                if(!WorkflowEncoding.hash(file.content()).equals(file.sha256()))throw new IllegalArgumentException();
                int ordinal=0;
                if(Set.of("jest","vitest").contains(module.framework())) {
                    var json=encoding.decode(file.content(),tools.jackson.databind.JsonNode.class);
                    for(var suite:json.path("testResults"))for(var test:suite.path("assertionResults")) {
                        String status=switch(test.path("status").asString()){case "passed"->"PASSED";case "failed"->"FAILED";case "pending","todo","skipped","disabled"->"SKIPPED";default->throw new IllegalArgumentException();};
                        String path=relative(suite.path("name").asString(),directory);
                        result.add(item(file,ordinal++,suite.path("name").asString(),test.path("fullName").asString(test.path("title").asString()),status,path));
                    }
                }else {
                    var doc=WorkflowNativeTestReports.xmlDocument(file.content());var cases=doc.getElementsByTagName("testcase");
                    for(int i=0;i<cases.getLength();i++) {
                        var test=(Element)cases.item(i);String suite=test.getAttribute("classname");
                        String status=test.getElementsByTagName("failure").getLength()>0||test.getElementsByTagName("error").getLength()>0?"FAILED":test.getElementsByTagName("skipped").getLength()>0?"SKIPPED":"PASSED";
                        String path=test.getAttribute("file");
                        if(!path.isEmpty())path=relative(path,directory);
                        else if(module.framework().equals("pytest"))path=suite.replace('.','/')+".py";
                        else path=suite.split("\\$",2)[0].replace('.','/');
                        result.add(item(file,ordinal++,suite,test.getAttribute("name"),status,path));
                    }
                }
                if(result.size()>100000)throw new IllegalArgumentException();
            }
            if(report.counts().total()!=result.size()||report.counts().passed()!=result.stream().filter(c->c.status().equals("PASSED")).count()
                    ||report.counts().failed()!=result.stream().filter(c->c.status().equals("FAILED")).count()
                    ||report.counts().skipped()!=result.stream().filter(c->c.status().equals("SKIPPED")).count())throw new IllegalArgumentException();
            return result.stream().sorted(Comparator.comparing(Case::id)).toList();
        }catch(Exception invalid){throw WorkflowTestReviewContract.invalid("实际测试个案与原报告不一致，请检查固定执行证据。");}
    }
    static boolean matches(Case test,String path,SourceTestProfile.Module module) {
        if(test.testPath()==null||test.testPath().isBlank()||module.testRoots().stream().noneMatch(r->path.startsWith(r+"/")))return false;
        if(Set.of("junit","testng").contains(module.framework())) {
            var extensions=List.of(".java",".kt",".groovy",".scala");
            if(extensions.stream().anyMatch(test.testPath()::endsWith))return path.equals(test.testPath())||module.testRoots().stream().anyMatch(r->path.equals(r+"/"+test.testPath()));
            return extensions.stream().anyMatch(ext->path.endsWith("/"+test.testPath()+ext));
        }
        if(module.framework().equals("pytest")&&path.endsWith(".py")) {
            String stem=path.substring(0,path.length()-3).replace('/','.');
            if(test.suite().equals(stem)||test.suite().startsWith(stem+"."))return true;
            if(!module.root().equals(".")&&path.startsWith(module.root()+"/")) {
                stem=path.substring(module.root().length()+1,path.length()-3).replace('/','.');
                if(test.suite().equals(stem)||test.suite().startsWith(stem+"."))return true;
            }
        }
        return path.equals(test.testPath())||module.framework().equals("pytest")&&!module.root().equals(".")&&path.equals(module.root()+"/"+test.testPath());
    }
    private static Case item(WorkflowNativeTestReports.File file,int ordinal,String suite,String name,String status,String path) {
        if(name.isBlank()||name.length()>4000||suite.length()>4000)throw new IllegalArgumentException();
        return new Case(WorkflowEncoding.hash(file.path()+"\n"+file.sha256()+"\n"+ordinal),file.path(),suite,name,status,path);
    }
    private static String relative(String file,String directory) {
        Path path=Path.of(file),root=Path.of(directory);if(path.isAbsolute()){if(!path.normalize().startsWith(root))return null;path=root.relativize(path.normalize());}
        String value=path.normalize().toString().replace('\\','/');return value.startsWith("../")||value.equals("..")?null:value;
    }
}
