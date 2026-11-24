package com.tamada.travel.orchestrator.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamada.travel.orchestrator.service.idempotency.IdempotencyAcquisition;
import com.tamada.travel.orchestrator.service.idempotency.IdempotencyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Фильтр идемпотентности API запросов (IETF Idempotency-Key HTTP Header).
 * Перехватывает мутирующие запросы (POST, PUT, PATCH) с заголовком Idempotency-Key.
 * Обеспечивает:
 * 1. Немедленный возврат кэшированного ответа при повторе запроса (с заголовком Idempotent-Replayed: true).
 * 2. 409 Conflict при одновременных параллельных запросах с одним ключом.
 * 3. 422 Unprocessable Entity при попытке повторить ключ с другим телом запроса.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH");

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyFilter(
            org.springframework.beans.factory.ObjectProvider<IdempotencyService> idempotencyServiceProvider,
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        this.idempotencyService = idempotencyServiceProvider.getIfAvailable();
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
    }

    public IdempotencyFilter(
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (idempotencyService == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank() || !MUTATING_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        idempotencyKey = idempotencyKey.trim();
        CachedBodyHttpServletRequest requestWrapper;
        try {
            requestWrapper = new CachedBodyHttpServletRequest(request);
        } catch (com.tamada.travel.orchestrator.exception.PayloadTooLargeException ex) {
            log.warn("Payload too large on request to {}: {}", request.getRequestURI(), ex.getMessage());
            writeErrorResponse(response, HttpStatus.valueOf(413), ex.getMessage(), request.getRequestURI());
            return;
        }
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        byte[] requestBody = requestWrapper.getCachedBody();
        String payloadHash = computeSha256(requestBody);
        String requestPath = request.getRequestURI();

        IdempotencyAcquisition acquisition;
        try {
            acquisition = idempotencyService.acquire(
                    idempotencyKey,
                    requestPath,
                    payloadHash,
                    Duration.ofMinutes(5)
            );
        } catch (Exception ex) {
            log.warn("Exception during idempotency acquire for key={}: {}, treating as conflict", idempotencyKey, ex.getMessage());
            acquisition = IdempotencyAcquisition.conflict(null);
        }

        switch (acquisition.outcome()) {
            case REPLAY -> {
                log.info("Serving replayed idempotency response for key={}", idempotencyKey);
                response.setStatus(acquisition.record().getResponseStatus() != null ? acquisition.record().getResponseStatus() : 200);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(HEADER_IDEMPOTENT_REPLAYED, "true");
                if (acquisition.record().getResponseBody() != null) {
                    response.getWriter().write(acquisition.record().getResponseBody());
                }
            }
            case CONFLICT -> {
                log.warn("Idempotency conflict for key={}", idempotencyKey);
                writeErrorResponse(response, HttpStatus.CONFLICT,
                        "A request with idempotency key '" + idempotencyKey + "' is currently being processed", requestPath);
            }
            case PAYLOAD_MISMATCH -> {
                log.warn("Idempotency payload mismatch for key={}", idempotencyKey);
                writeErrorResponse(response, HttpStatus.UNPROCESSABLE_CONTENT,
                        "Idempotency key '" + idempotencyKey + "' was previously used with a different request payload", requestPath);
            }
            case PROCEED -> {
                try {
                    filterChain.doFilter(requestWrapper, responseWrapper);
                    int status = responseWrapper.getStatus();
                    byte[] responseBytes = responseWrapper.getContentAsByteArray();
                    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

                    if (status < 500) {
                        idempotencyService.markCompleted(idempotencyKey, status, responseBody);
                    } else {
                        idempotencyService.markFailed(idempotencyKey);
                    }
                } catch (Exception ex) {
                    idempotencyService.markFailed(idempotencyKey);
                    throw ex;
                } finally {
                    responseWrapper.copyBodyToResponse();
                }
            }
        }
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", path
        );
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }

    private String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data != null ? data : new byte[0]);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private static final int MAX_BODY_SIZE = 2 * 1024 * 1024; // 2 MB
        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            int contentLength = request.getContentLength();
            if (contentLength > MAX_BODY_SIZE) {
                throw new com.tamada.travel.orchestrator.exception.PayloadTooLargeException(
                        "Request payload exceeds maximum allowed size of " + MAX_BODY_SIZE + " bytes"
                );
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            int totalBytes = 0;
            try (InputStream is = request.getInputStream()) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                    if (totalBytes > MAX_BODY_SIZE) {
                        throw new com.tamada.travel.orchestrator.exception.PayloadTooLargeException(
                                "Request payload exceeds maximum allowed size of " + MAX_BODY_SIZE + " bytes"
                        );
                    }
                    baos.write(buffer, 0, bytesRead);
                }
            }
            this.cachedBody = baos.toByteArray();
        }

        public byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
