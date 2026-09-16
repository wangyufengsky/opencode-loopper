package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.TaskFailure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Git 2.30 compatible command-scope configuration; secrets never appear in argv or files. */
public final class GitHttpAuthentication {
    private GitHttpAuthentication() { }
    public static String origin(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            int port = uri.getPort();
            if (port == 0 || port > 65535) throw new IllegalArgumentException();
            if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, null, null, null).toASCIIString();
        } catch (Exception invalid) {
            throw new TaskFailure("GIT_CREDENTIAL_URL_INVALID", "请填写不含账号、密码或查询参数的 HTTP(S) Git 地址");
        }
    }
    public static Map<String, String> environment(String server, String username, String secret, String target) {
        if (!server.equals(origin(target))) throw new TaskFailure("GIT_CREDENTIAL_HOST_MISMATCH", "Git 地址与凭据绑定的服务器不一致，请检查账号设置");
        String header = Base64.getEncoder().encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        // GIT_CONFIG_COUNT is unavailable in 2.30.2. The quoted parameter channel is used by git -c itself.
        List<String> config = List.of("credential.helper=", "core.askPass=", "http.extraHeader=",
                "http." + server + "/.extraHeader=", "http." + server + "/.extraHeader=Authorization: Basic " + header,
                "http.followRedirects=false", "http.saveCookies=false", "http.cookieFile=", "submodule.recurse=false",
                "fetch.recurseSubmodules=false", "protocol.ext.allow=never");
        return Map.of("GIT_CONFIG_PARAMETERS", config.stream().map(s -> "'" + s.replace("'", "'\\''") + "'")
                .collect(java.util.stream.Collectors.joining(" ")), "GIT_TERMINAL_PROMPT", "0", "GIT_TRACE", "0",
                "GIT_TRACE_CURL", "0", "GIT_TRACE2", "0", "GIT_TRACE2_EVENT", "0", "GIT_TRACE2_PERF", "0");
    }
}
