package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.Map;

/** Only trusted Git network operations may request a short-lived child environment. */
public interface GitCredentialProvider {
    Map<String, String> environment(Path registeredProject, String remoteUrl);
    default Scope scope(Path registeredProject) { return new Scope("", false, Map.of()); }
    /** Ephemeral origin-bound grant for the supervised Git helper; never serialize into a request or log. */
    record Scope(String origin, boolean strict, Map<String,String> environment) {
        public Scope { environment=Map.copyOf(environment); }
        @Override public String toString(){return "GitCredentialScope[redacted]";}
        public Map<String,String> forRemote(String remote) {
            if(origin.isEmpty())return Map.of();
            if(!GitHttpAuthentication.origin(remote).equals(origin)) {
                if(strict)throw new io.opencode.loopper.domain.TaskFailure("GIT_CREDENTIAL_HOST_MISMATCH","项目独立账号与 Git 服务器不匹配，请检查项目 Git 账号");
                return Map.of();
            }
            return environment;
        }
        public Map<String,String> forWorker() {
            var values=new java.util.HashMap<>(environment);
            values.put("LOOPPER_SNAPSHOT_GIT_ORIGIN",origin);values.put("LOOPPER_SNAPSHOT_GIT_STRICT",Boolean.toString(strict));
            return Map.copyOf(values);
        }
        public static Scope fromWorker() {
            var values=new java.util.HashMap<String,String>();
            for(String name:java.util.List.of("GIT_CONFIG_PARAMETERS","GIT_TERMINAL_PROMPT","GIT_TRACE","GIT_TRACE_CURL","GIT_TRACE2","GIT_TRACE2_EVENT","GIT_TRACE2_PERF"))
                if(System.getenv(name)!=null)values.put(name,System.getenv(name));
            return new Scope(System.getenv().getOrDefault("LOOPPER_SNAPSHOT_GIT_ORIGIN",""),
                    Boolean.parseBoolean(System.getenv().getOrDefault("LOOPPER_SNAPSHOT_GIT_STRICT","false")),values);
        }
    }
}
