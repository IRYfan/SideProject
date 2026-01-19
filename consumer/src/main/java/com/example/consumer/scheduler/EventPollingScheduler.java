package com.example.consumer.scheduler;

import com.example.consumer.service.EventConsumerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for polling events from producer
 * Runs every 5 seconds by default
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class EventPollingScheduler {

    private final EventConsumerService eventConsumerService;

    /**
     * Poll producer every 5 seconds
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void pollProducer() {
        log.debug("Starting scheduled poll...");
        eventConsumerService.pollOnce();
    }
}
