package com.tamada.travel.orchestrator.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Фильтр проверки API-ключа / авторизации (OWASP A01: Broken Access Control).
 * Контролируется флагом security.api-key.enabled (по умолчанию false для совместимости).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public ApiKeyAuthFilter(
            ObjectProvider<SecurityProperties> propertiesProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        this.properties = propertiesProvider.getIfAvailable(SecurityProperties::new);
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (properties == null || !properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        // Skip actuator and swagger docs
        if (path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            filterChain.doFilter(request, response);
            return;
        }

        String headerName = properties.headerName() != null ? properties.headerName() : "X-API-KEY";
        String providedKey = request.getHeader(headerName);

        boolean isKeyValid = providedKey != null
                && properties.apiKey() != null
                && java.security.MessageDigest.isEqual(
                        providedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        properties.apiKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        if (!isKeyValid) {
            log.warn("Unauthorized request to {}: missing or invalid API key", path);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> errorBody = Map.of(
                    "timestamp", Instant.now().toString(),
                    "status", HttpStatus.UNAUTHORIZED.value(),
                    "error", HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                    "message", "Missing or invalid API key in header " + headerName,
                    "path", path
            );
            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
