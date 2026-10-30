package com.example.gpsengine.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter placed after JWT authentication in the Spring Security filter chain
 * to populate opaque userId and tenantId into SLF4J MDC for structured logging.
 *
 * Guaranteed to never log usernames, passwords, or personal data.
 */
public class SecurityMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) {
                MDC.put("userId", sub.trim());
            }
            String tenant = jwt.getClaimAsString("tenant");
            if (tenant != null && !tenant.isBlank()) {
                MDC.put("tenantId", tenant.trim());
            }
        }

        filterChain.doFilter(request, response);
    }
}

