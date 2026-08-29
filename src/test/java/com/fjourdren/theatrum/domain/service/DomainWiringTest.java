package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.EncodeMetricsPort;
import com.fjourdren.theatrum.application.port.out.EncoderPort;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the wiring: every domain service must be reachable by component scan alone. Before the
 * domain was allowed to carry Spring annotations these were hand-written {@code @Bean} methods in
 * {@code BeanConfig}; this test is what stops them from silently drifting back to unwired classes.
 */
class DomainWiringTest {

    @Configuration
    @ComponentScan("com.fjourdren.theatrum.domain.service")
    static class ScanDomainServices {
    }

    @Test
    void everyDomainServiceIsDiscoveredByComponentScan() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScanDomainServices.class)
                .withBean(LoadedConfiguration.class, () -> new LoadedConfiguration(null, null, Map.of()))
                .withBean(AppPaths.class, () -> new AppPaths(Path.of("data"), Path.of("frontend")))
                .withBean(StoragePort.class, () -> mock(StoragePort.class))
                .withBean(EncoderPort.class, () -> mock(EncoderPort.class))
                .withBean(EncodeMetricsPort.class, () -> mock(EncodeMetricsPort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(PathTemplateService.class)
                        .hasSingleBean(LiveStreamRegistry.class)
                        .hasSingleBean(ApplicationService.class)
                        .hasSingleBean(RtmpAuthService.class)
                        .hasSingleBean(StreamService.class)
                        .hasSingleBean(ViewerTracker.class)
                        .hasSingleBean(EncodeService.class)
                        .hasSingleBean(EncodeJobQueue.class)
                        .hasSingleBean(VideoUnencodedDetector.class));
    }
}
