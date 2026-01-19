package com.example.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event model for realtime analytics system
 * Represents a fact or state change in the queue system
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    /**
     * Unique event identifier (UUID)
     */
    private String eventId;

    /**
     * Event timestamp (when event occurred)
     */
    private Instant timestamp;

    /**
     * Event type classification
     */
    private EventType eventType;

    /**
     * Queue identifier (primary dimension for aggregation)
     */
    private String queueId;

    /**
     * Agent identifier (optional secondary dimension)
     */
    private String agentId;

    /**
     * Interaction identifier for tracking related events
     */
    private String interactionId;

    /**
     * Flexible payload for event-specific data
     */
    private Map<String, Object> payload;

    /**
     * Factory method to create an event with auto-generated ID and current
     * timestamp
     */
    public static Event create(EventType eventType, String queueId, String agentId) {
        return Event.builder()
                .eventId(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .eventType(eventType)
                .queueId(queueId)
                .agentId(agentId)
                .build();
    }
}
