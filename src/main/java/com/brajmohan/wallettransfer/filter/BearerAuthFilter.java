package com.brajmohan.wallettransfer.filter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Requires Authorization: Bearer <token> on every request except /healthz
// and /metrics (Docker's healthcheck and a Prometheus scraper don't carry
// one). Authenticates the caller only -- doesn't check that a transfer's
// from_user_id matches the token's own user.
@Component
@Order(1)
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_USER = "authenticatedUser";

    private static final Set<String> EXEMPT_PATHS = Set.of("/healthz", "/metrics");
    private static final String BEARER_PREFIX = "Bearer ";

    private final Map<String, String> tokens;

    public BearerAuthFilter(Map<String, String> bearerTokens) {
        this.tokens = bearerTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (EXEMPT_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith(BEARER_PREFIX))
                ? header.substring(BEARER_PREFIX.length())
                : null;
        String user = token == null ? null : tokens.get(token);

        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid bearer token\"}");
            return;
        }

        request.setAttribute(ATTRIBUTE_USER, user);
        chain.doFilter(request, response);
    }
}
