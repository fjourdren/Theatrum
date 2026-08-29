package com.fjourdren.theatrum.infrastructure.adapter.in.web;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prometheus scrape endpoint, matching Go's {@code promhttp.Handler()} at {@code /metrics}. */
@RequiredArgsConstructor
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;

    @GetMapping(value = HttpConstants.METRICS_PATH, produces = HttpConstants.CONTENT_TYPE_PROMETHEUS)
    public String scrape() {
        return registry.scrape();
    }
}
