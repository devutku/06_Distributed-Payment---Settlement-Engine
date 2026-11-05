package com.bank.payment.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Check if endpoint is POST /api/v1/transfers
        if ("POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/v1/transfers")) {
            String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
            if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("""
                    {
                      "type": "about:blank",
                      "title": "Missing Idempotency-Key Header",
                      "status": 400,
                      "detail": "Para transferi isteklerinde 'Idempotency-Key' başlığı zorunludur."
                    }
                    """);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}

