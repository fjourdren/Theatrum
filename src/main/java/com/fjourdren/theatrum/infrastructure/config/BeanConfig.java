package com.fjourdren.theatrum.infrastructure.config;

import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.service.ApplicationService;
import com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.config.RtmpConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What component scanning cannot reach: third-party types and values derived from the config file.
 *
 * <p>Domain services carry {@code @Component} and wire themselves. {@code LoadedConfiguration} is
 * parsed before the context exists and registered as a pre-existing singleton by
 * {@code TheatrumApplication}.
 */
@Configuration
public class BeanConfig {

    /**
     * micrometer-registry-prometheus alone does not auto-configure a registry
     * (spring-boot-actuator-autoconfigure is not on the classpath), so declare it here.
     * Its scrape() output is what GET /metrics serves, matching the Go promhttp endpoint.
     */
    @Bean
    PrometheusMeterRegistry meterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /** A value object, not a component: built from the working directory, overridden in tests. */
    @Bean
    AppPaths appPaths() {
        return AppPaths.defaults();
    }

    @Bean
    RtmpConfig rtmpConfig(ApplicationService applicationService) {
        var rtmp = applicationService.getServer().rtmp();
        return new RtmpConfig(rtmp.reconnectDelay(), rtmp.cleanupDelay());
    }
}
