package com.example.consumer.service;

import com.example.consumer.model.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response model for producer's poll endpoint
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PollResponse {

    /**
     * List of events returned in this poll
     */
    private List<Event> events;

    /**
     * Cursor for next poll (to avoid re-fetching)
     */
    private int nextCursor;

    /**
     * Epoch identifier for the current event sequence
     */
    private String epoch;

    /**
     * Whether there are more events available
     */
    private boolean hasMore;
}
