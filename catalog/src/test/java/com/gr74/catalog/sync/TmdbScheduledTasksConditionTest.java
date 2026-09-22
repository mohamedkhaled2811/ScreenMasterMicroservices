package com.gr74.catalog.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.gr74.catalog.service.TmdbSyncService;

/** Scheduler bean exists only when {@code tmdb.enabled=true}. */
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
