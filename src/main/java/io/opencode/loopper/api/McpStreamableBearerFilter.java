package io.opencode.loopper.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces the same fail-closed bearer policy on every Streamable HTTP MCP method (POST/GET/DELETE). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class McpStreamableBearerFilter extends OncePerRequestFilter {
    static final String STREAMABLE_PATH = "/api/mcp-streamable";
    private final McpTokenProvider token;

    McpStreamableBearerFilter(McpTokenProvider token) { this.token = token; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STREAMABLE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!token.matches(request.getHeader("Authorization"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"MCP bearer token required\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
