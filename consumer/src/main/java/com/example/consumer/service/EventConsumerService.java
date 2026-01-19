package com.example.consumer.service;

import com.example.consumer.model.Event;
import com.example.consumer.model.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for consuming events from producer
 * Periodically polls events and aggregates them
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventConsumerService {

    private final RestTemplate restTemplate;

    @Value("${producer.url:http://localhost:8080}")
    private String producerUrl;

    @Value("${consumer.cursor.file:data/consumer-cursor.txt}")
    private String cursorFilePath;

    // Cursor to track last consumed event
    private final AtomicInteger lastCursor = new AtomicInteger(-1);
    private final AtomicReference<String> lastEpoch = new AtomicReference<>(null);

    // Simple aggregation: count events per queue
    private final Map<String, Integer> eventCountByQueue = Collections.synchronizedMap(new HashMap<>());

    // Observability counters
    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong lastLagMillis = new AtomicLong(0);

    @PostConstruct
    public void initCursor() {
        CursorState state = loadCursorFromFile();
        lastCursor.set(state.cursor);
        lastEpoch.set(state.epoch);
        log.info("Loaded cursor: {}, epoch: {} from {}", state.cursor, state.epoch, cursorFilePath);
    }

    /**
     * Poll events from producer once
     */
    public synchronized void pollOnce() {
        int cursor = lastCursor.get();
        String pollUrl = String.format("%s/v1/events/poll?after=%d&limit=100", producerUrl, cursor);

        try {
            log.info("Polling producer at: {}", pollUrl);
            PollResponse response = restTemplate.getForObject(pollUrl, PollResponse.class);

            if (response == null) {
                log.info("No response from producer");
                return;
            }

            String responseEpoch = response.getEpoch();
            if (responseEpoch != null) {
                String currentEpoch = lastEpoch.get();
                if (currentEpoch == null) {
                    lastEpoch.set(responseEpoch);
                } else if (!currentEpoch.equals(responseEpoch)) {
                    resetForNewEpoch(currentEpoch, responseEpoch);
                    return;
                }
            }

            if (response.getEvents() == null || response.getEvents().isEmpty()) {
                log.info("No new events from producer");
                return;
            }

            // Process each event
            for (Event event : response.getEvents()) {
                processEvent(event);
            }

            // Update cursor
            lastCursor.set(response.getNextCursor());
            saveCursorToFile(response.getNextCursor(), lastEpoch.get());
            log.info("Polled {} events, new cursor: {}, totalConsumed: {}, lastLagMs: {}",
                    response.getEvents().size(),
                    response.getNextCursor(),
                    totalConsumed.get(),
                    lastLagMillis.get());

        } catch (Exception e) {
            log.error("Failed to poll events from producer", e);
        }
    }

    /**
     * Process a single event (aggregate)
     */
    private void processEvent(Event event) {
        totalConsumed.incrementAndGet();
        if (event.getTimestamp() != null) {
            long lagMs = Math.max(0, Instant.now().toEpochMilli() - event.getTimestamp().toEpochMilli());
            lastLagMillis.set(lagMs);
        }
        if (event.getEventType() == EventType.ENQUEUED) {
            eventCountByQueue.merge(event.getQueueId(), 1, Integer::sum);
            log.debug("ENQUEUED event for queue: {}", event.getQueueId());
        } else if (event.getEventType() == EventType.DEQUEUED) {
            eventCountByQueue.merge(event.getQueueId(), -1, Integer::sum);
            log.debug("DEQUEUED event for queue: {}", event.getQueueId());
        }
    }

    /**
     * Get current queue metrics
     */
    public Map<String, Integer> getQueueMetrics() {
        return new HashMap<>(eventCountByQueue);
    }

    /**
     * Get metric for a specific queue
     */
    public int getQueueCount(String queueId) {
        return eventCountByQueue.getOrDefault(queueId, 0);
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }

    public int getLastCursor() {
        return lastCursor.get();
    }

    public long getLastLagMillis() {
        return lastLagMillis.get();
    }

    public String getEpoch() {
        return lastEpoch.get();
    }

    private void resetForNewEpoch(String oldEpoch, String newEpoch) {
        lastCursor.set(-1);
        lastEpoch.set(newEpoch);
        eventCountByQueue.clear();
        totalConsumed.set(0);
        lastLagMillis.set(0);
        saveCursorToFile(-1, newEpoch);
        log.warn("Producer epoch changed from {} to {}, reset cursor and metrics", oldEpoch, newEpoch);
    }

    private CursorState loadCursorFromFile() {
        Path path = Paths.get(cursorFilePath);
        if (!Files.exists(path)) {
            return new CursorState(-1, null);
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return new CursorState(-1, null);
            }
            String[] lines = content.split("\\R");
            Integer cursor = null;
            String epoch = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("cursor=")) {
                    cursor = Integer.parseInt(trimmed.substring("cursor=".length()));
                } else if (trimmed.startsWith("epoch=")) {
                    epoch = trimmed.substring("epoch=".length());
                } else if (trimmed.matches("-?\\d+")) {
                    cursor = Integer.parseInt(trimmed);
                }
            }
            return new CursorState(cursor != null ? cursor : -1, epoch);
        } catch (Exception e) {
            log.warn("Failed to read cursor file {}, defaulting to -1", cursorFilePath, e);
            return new CursorState(-1, null);
        }
    }

    private void saveCursorToFile(int cursor, String epoch) {
        Path path = Paths.get(cursorFilePath);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder content = new StringBuilder();
            if (epoch != null && !epoch.isBlank()) {
                content.append("epoch=").append(epoch).append(System.lineSeparator());
            }
            content.append("cursor=").append(cursor).append(System.lineSeparator());
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write cursor file {}", cursorFilePath, e);
        }
    }

    private static class CursorState {
        private final int cursor;
        private final String epoch;

        private CursorState(int cursor, String epoch) {
            this.cursor = cursor;
            this.epoch = epoch;
        }
    }
}
