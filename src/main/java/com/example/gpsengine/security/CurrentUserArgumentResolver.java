package com.example.gpsengine.security;

import com.example.gpsengine.domain.Role;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} controller arguments from the verified JWT in SecurityContext.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Long userId = null;
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) {
                try {
                    userId = Long.parseLong(sub);
                } catch (NumberFormatException ignored) {}
            }

            String tenantId = jwt.getClaimAsString("tenant");
            String roleStr = jwt.getClaimAsString("role");
            Role role = null;
            if (roleStr != null && !roleStr.isBlank()) {
                try {
                    role = Role.valueOf(roleStr);
                } catch (IllegalArgumentException ignored) {}
            }
            String employeeRef = jwt.getClaimAsString("employeeRef");

            return new CurrentUser(userId, tenantId, role, employeeRef);
        }
        return null;
    }
}

