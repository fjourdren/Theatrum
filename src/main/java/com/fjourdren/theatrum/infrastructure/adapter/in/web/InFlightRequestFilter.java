package com.fjourdren.theatrum.infrastructure.adapter.in.web;

import com.fjourdren.theatrum.infrastructure.adapter.out.metrics.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Keeps the in-flight request gauge up to date. Go did this with a handler wrapper. */
@RequiredArgsConstructor
@Component
public class InFlightRequestFilter extends OncePerRequestFilter {

    private final Metrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        metrics.incHttpRequestsInFlight();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.decHttpRequestsInFlight();
        }
    }
}
