package com.claims.intake.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class AsyncConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs one claim's workflow (after its documents are verified). */
    @Bean(name = "workflowExecutor")
    ThreadPoolTaskExecutor workflowExecutor() {
        return pool("workflow-", 2, 4, 200);
    }

    /**
     * The eligibility and assessment calls, in parallel. Separate from the workflow pool, so a workflow
     * waiting for its two calls can never starve them. Bounded: when full, the caller runs the call itself.
     */
    @Bean(name = "checkExecutor")
    ThreadPoolTaskExecutor checkExecutor() {
        return pool("check-", 4, 8, 100);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setThreadNamePrefix(prefix);
        e.setCorePoolSize(core);
        e.setMaxPoolSize(max);
        e.setQueueCapacity(queue);
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(15);
        return e;
    }
}
