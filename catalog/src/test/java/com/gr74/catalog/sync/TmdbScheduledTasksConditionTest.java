package com.gr74.catalog.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.gr74.catalog.service.TmdbSyncService;

/**
 * Proves the enablement guard on {@link TmdbScheduledTasks}: the bean exists only when
 * {@code tmdb.enabled=true}. This is what lets the test profile ({@code enabled=false}) boot the full
 * context with no {@code TMDB_API_KEY} and no risk of a background sync firing — verified here rather
 * than relying on it implicitly.
 *
 * <p>The component is registered via {@code @Import} (not {@code withBean}) so its class-level
 * {@code @ConditionalOnProperty} is actually evaluated — programmatic bean registration bypasses the
 * condition.
 */
class TmdbScheduledTasksConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubConfig.class, ImportTasks.class);

    @Test
    void schedulerBeanPresentWhenEnabled() {
        runner.withPropertyValues("tmdb.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TmdbScheduledTasks.class));
    }

    @Test
    void schedulerBeanAbsentWhenDisabled() {
        runner.withPropertyValues("tmdb.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TmdbScheduledTasks.class));
    }

    @Test
    void schedulerBeanAbsentWhenPropertyMissing() {
        // No havingValue match when the property is absent entirely — the guard defaults to off.
        runner.run(context -> assertThat(context).doesNotHaveBean(TmdbScheduledTasks.class));
    }

    @Configuration
    static class StubConfig {
        @Bean
        TmdbSyncService tmdbSyncService() {
            return mock(TmdbSyncService.class);
        }
    }

    @Configuration
    @Import(TmdbScheduledTasks.class)
    static class ImportTasks {
    }
}
