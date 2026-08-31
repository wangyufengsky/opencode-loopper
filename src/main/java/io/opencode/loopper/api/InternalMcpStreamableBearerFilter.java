package io.opencode.loopper.api;

import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Restricts the private Streamable MCP to the active managed generation on loopback. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class InternalMcpStreamableBearerFilter extends OncePerRequestFilter {
    private final InternalMcpRuntimeAccess access;

    InternalMcpStreamableBearerFilter(InternalMcpRuntimeAccess access) {
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !InternalMcpContractCatalog.ENDPOINT_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!loopback(request.getRemoteAddr())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Internal MCP is loopback-only");
            return;
        }
        if (access.current().isEmpty()) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Internal MCP generation is inactive");
            return;
        }
        if (!access.matchesBearer(request.getHeader("Authorization"))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Internal MCP bearer token required");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean loopback(String address) {
        if (address == null || address.isBlank()) return false;
        return switch (address.toLowerCase(Locale.ROOT)) {
            case "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1" -> true;
            default -> false;
        };
    }

    private static void reject(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + detail + "\"}");
    }
}
