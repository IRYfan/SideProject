package com.learning.producer.repository;

import com.learning.producer.model.Event;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/**
 * In-memory event repository for Phase 1
 * Stores events in a simple list with cursor support
 */
@Repository
public class EventRepository {

    private final List<Event> events = new ArrayList<>();
    private final AtomicLong eventCounter = new AtomicLong(0);
    private final String epoch = UUID.randomUUID().toString();

    /**
     * Add a new event to the store
     */
    public synchronized Event add(Event event) {
        events.add(event);
        eventCounter.incrementAndGet();
        return event;
    }

    /**
     * Get all events after a given index (cursor)
     *
     * @param afterIndex 0-based index, fetch events after this index
     * @param limit      maximum number of events to return
     * @return list of events
     */
    public synchronized List<Event> getAfter(int afterIndex, int limit) {
        int startIdx = afterIndex + 1;
        if (startIdx >= events.size()) {
            return new ArrayList<>();
        }

        int endIdx = Math.min(startIdx + limit, events.size());
        return new ArrayList<>(events.subList(startIdx, endIdx));
    }

    /**
     * Get total event count
     */
    public long getTotalCount() {
        return eventCounter.get();
    }

    /**
     * Get current size of event list
     */
    public int getSize() {
        return events.size();
    }

    /**
     * Epoch for the current in-memory event sequence
     */
    public String getEpoch() {
        return epoch;
    }
}
