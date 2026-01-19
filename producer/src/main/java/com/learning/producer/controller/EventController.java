package com.learning.producer.controller;

import com.learning.producer.model.Event;
import com.learning.producer.model.EventType;
import com.learning.producer.service.EventService;
import com.learning.producer.service.PollResponse;
import com.learning.producer.service.SystemStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Event API controller for producer service
 * Handles event creation and polling
 */
@Slf4j
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * Create a single event
     * POST /v1/events
     *
     * @param request event creation request
     * @return created event
     */
    @PostMapping
    public ResponseEntity<Event> createEvent(@RequestBody CreateEventRequest request) {
        log.info("Creating event: type={}, queueId={}", request.getEventType(), request.getQueueId());
        Event event = eventService.createEvent(
                request.getEventType(),
                request.getQueueId(),
                request.getAgentId());
        return ResponseEntity.ok(event);
    }

    /**
     * Poll events after a cursor
     * GET /v1/events/poll?after=<index>&limit=<n>
     *
     * @param after cursor index (default -1, meaning from start)
     * @param limit max events to return (default 100)
     * @return PollResponse with events and nextCursor
     */
    @GetMapping("/poll")
    public ResponseEntity<PollResponse> pollEvents(
            @RequestParam(value = "after", defaultValue = "-1") int after,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {

        if (limit <= 0 || limit > 1000) {
            limit = 100;
        }

        PollResponse response = eventService.pollEvents(after, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * Get system stats
     * GET /v1/events/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<SystemStats> getStats() {
        return ResponseEntity.ok(eventService.getStats());
    }

    /**
     * Health check
     * GET /v1/events/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Request model for creating an event
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CreateEventRequest {
        private EventType eventType;
        private String queueId;
        private String agentId;
    }
}
