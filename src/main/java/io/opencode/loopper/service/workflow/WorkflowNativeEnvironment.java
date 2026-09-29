package io.opencode.loopper.service.workflow;

import io.opencode.loopper.runtime.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.Preparation;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.template.SourceTestProfile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Compiles project-declared dependencies into the same durable command request; never installs here. */
@Component
public final class WorkflowNativeEnvironment {
    private final String os;
    private final Map<String,String> environment;
    private final tools.jackson.databind.ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowNativeEnvironment(tools.jackson.databind.ObjectMapper json){this(System.getProperty("os.name",""),System.getenv(),json);}
    WorkflowNativeEnvironment(String os,Map<String,String> environment,tools.jackson.databind.ObjectMapper json){this.os=os;this.environment=Map.copyOf(environment);this.json=json;}
    public record Prepared(List<String> argv,List<Preparation> preparations){public Prepared{argv=List.copyOf(argv);preparations=List.copyOf(preparations);}}
    public Prepared compile(Path root,SourceTestProfile.Module module,List<String> command) {
        try {
            Path directory=root.resolve(module.root()).normalize();DurableCommandProtocol.check(directory);
            if(!directory.startsWith(root)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw invalid("测试模块目录不可用。");
            return switch(module.framework()) {
                case "jest","vitest" -> npm(root,directory,command);
                case "pytest" -> python(root,directory,command);
                case "junit","testng" -> new Prepared(wrapper(root,directory,command),List.of());
                default -> throw invalid("没有可用的原生测试环境配置。");
            };
        }catch(IOException failure){throw invalid("无法安全读取测试依赖配置，请检查固定代码中的配置文件。");}
    }
    private Prepared npm(Path root,Path module,List<String> command)throws IOException {
        Path install=module;boolean locked=false;
        for(Path at=module;at!=null&&at.startsWith(root);at=at.getParent()) {
            Path lock=file(at.resolve("npm-shrinkwrap.json"))?at.resolve("npm-shrinkwrap.json"):at.resolve("package-lock.json");
            if(file(lock)&&(at.equals(module)||workspaceLock(lock,at.relativize(module).toString().replace('\\','/')))){install=at;locked=true;break;}
        }
        if(!file(install.resolve("package.json")))throw invalid("npm 依赖目录缺少 package.json。");
        absent(install.resolve("node_modules"));
        var argv=new ArrayList<>(List.of("npm",locked?"ci":"install","--prefix",install.toString(),"--include=dev","--no-audit","--no-fund"));
        if(!locked)argv.add("--package-lock=false");
        return new Prepared(command,List.of(step("NPM_INSTALL",install,argv)));
    }
    private Prepared python(Path root,Path module,List<String> command)throws IOException {
        Path env=root.resolve(".loopper-test-env");absent(env);
        String executable=env.resolve(windows()?"Scripts/python.exe":"bin/python").toString();
        var install=new ArrayList<>(List.of(executable,"-m","pip","install","--disable-pip-version-check","--no-input"));
        Path dependencyRoot=pythonDependencies(root,module,install);
        var create=new ArrayList<>(pythonExecutable(root));create.addAll(List.of("-m","venv",env.toString()));
        var argv=new ArrayList<>(command);argv.set(0,executable);
        // The private interpreter does not exist until the first persisted preparation has completed.
        return new Prepared(argv,List.of(step("PYTHON_ENV",root,create),new Preparation("PYTHON_INSTALL",dependencyRoot.toString(),install)));
    }
    private Path pythonDependencies(Path root,Path module,List<String> install)throws IOException {
        for(Path at=module;at!=null&&at.startsWith(root);at=at.getParent()) {
            boolean dependencies=false;
            for(String name:List.of("requirements.txt","requirements-test.txt","requirements-dev.txt")) {
                if(file(at.resolve(name))){install.add("-r");install.add(name);dependencies=true;}
            }
            String project=file(at.resolve("pyproject.toml"))?text(at.resolve("pyproject.toml")):"";
            if(file(at.resolve("setup.py"))||table(project,"project")||table(project,"build-system")||table(project,"tool.poetry")) {
                String extra=testExtra(project);install.add(extra==null?".":".["+extra+"]");dependencies=true;
            }
            if(dependencies)return at;
        }
        throw new BadRequestException("WORKFLOW_TEST_DEPENDENCIES_REQUIRED","Python 项目需在 requirements.txt、requirements-test.txt、requirements-dev.txt 或包配置中声明测试依赖（包括 pytest）。");
    }
    private List<String> wrapper(Path root,Path module,List<String> command)throws IOException {
        String program=command.getFirst();if(!Set.of("mvn","gradle").contains(program))return command;
        int config=command.indexOf(program.equals("mvn")?"-f":"-p");
        if(config>=0&&config+1<command.size()) {
            Path selected=root.resolve(command.get(config+1)).normalize();module=program.equals("mvn")?selected.getParent():selected;
            if(module==null||!module.startsWith(root))throw invalid("测试构建入口超出固定代码目录。");
        }
        String name=program.equals("mvn")?(windows()?"mvnw.cmd":"mvnw"):(windows()?"gradlew.bat":"gradlew");
        for(Path at=module;at!=null&&at.startsWith(root);at=at.getParent()) {
            Path wrapper=at.resolve(name);if(!file(wrapper))continue;
            if(!windows()&&!Files.isExecutable(wrapper))throw new BadRequestException("WORKFLOW_TEST_WRAPPER_UNEXECUTABLE","项目测试启动脚本没有执行权限，请修正项目文件权限后重新准备。");
            var actual=new ArrayList<>(command);actual.set(0,wrapper.toString());return actual;
        }
        return command;
    }
    private List<String> pythonExecutable(Path root) {
        if(windows()) {
            var resolver=new ExecutableResolver(os,environment);
            for(String program:List.of("python3","python","py"))try {
                return resolver.resolve(root,program.equals("py")?List.of("py","-3"):List.of(program)).argv();
            }catch(io.opencode.loopper.domain.TaskFailure ignored){ }
        }else {
            for(String program:List.of("python3","python"))for(String entry:environment.getOrDefault("PATH","").split(java.io.File.pathSeparator)) {
                if(entry.isBlank())continue;Path path=Path.of(entry).resolve(program);
                if(Files.isRegularFile(path)&&Files.isExecutable(path))return List.of(path.toAbsolutePath().normalize().toString());
            }
        }
        throw new BadRequestException("WORKFLOW_TEST_PYTHON_UNAVAILABLE","未找到可用的 Python 3，请安装并将解释器加入 Loopper 进程的 PATH。");
    }
    private Preparation step(String name,Path directory,List<String> argv) {
        return new Preparation(name,directory.toString(),new ExecutableResolver(os,environment).resolve(directory,argv).argv());
    }
    private static boolean file(Path path)throws IOException {
        DurableCommandProtocol.check(path);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw invalid("测试依赖配置不是普通文件。");return true;
    }
    private static void absent(Path path)throws IOException {DurableCommandProtocol.check(path);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new BadRequestException("WORKFLOW_TEST_ENVIRONMENT_EXISTS","私有依赖目录已有内容，请保留现场并新建测试尝试，不能覆盖旧环境。");}
    private static String text(Path path)throws IOException {
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(256*1024+1);if(bytes.length>256*1024)throw invalid("测试包配置过大。");return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }
    private boolean workspaceLock(Path lock,String module)throws IOException {
        try(var input=Files.newInputStream(lock,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw invalid("npm 工作区锁文件过大。");
            try{return json.readTree(bytes).path("packages").hasNonNull(module);}
            catch(RuntimeException malformed){throw invalid("npm 工作区锁文件无法解析。");}
        }
    }
    private static boolean table(String text,String name){return Pattern.compile("(?m)^\\s*\\[\\s*"+Pattern.quote(name)+"\\s*]\\s*(?:#.*)?$").matcher(text).find();}
    private static String testExtra(String text) {
        var section=Pattern.compile("(?ms)^\\s*\\[project\\.optional-dependencies]\\s*\\n(.*?)(?=^\\s*\\[|\\z)").matcher(text);
        if(!section.find())return null;
        for(String name:List.of("test","tests","testing"))if(Pattern.compile("(?m)^\\s*(?:"+name+"|'"+name+"'|\""+name+"\")\\s*=").matcher(section.group(1)).find())return name;
        return null;
    }
    private boolean windows(){return os.toLowerCase(Locale.ROOT).contains("win");}
    private static BadRequestException invalid(String message){return new BadRequestException("WORKFLOW_TEST_ENVIRONMENT_INVALID",message);}
}
