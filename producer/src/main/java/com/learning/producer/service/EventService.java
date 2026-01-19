package com.learning.producer.service;

import com.learning.producer.model.Event;
import com.learning.producer.model.EventType;
import com.learning.producer.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for event management
 * Handles business logic for creating and retrieving events
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    /**
     * Create and store a new event
     */
    public Event createEvent(EventType eventType, String queueId, String agentId) {
        Event event = Event.create(eventType, queueId, agentId);
        eventRepository.add(event);
        log.info("Event created: id={}, type={}, queueId={}", event.getEventId(), eventType, queueId);
        return event;
    }

    /**
     * Poll events after a given cursor
     *
     * @param afterIndex cursor index (0-based), events after this index
     * @param limit      number of events to fetch
     * @return PollResponse containing events and nextCursor
     */
    public PollResponse pollEvents(int afterIndex, int limit) {
        List<Event> events = eventRepository.getAfter(afterIndex, limit);
        int nextCursor = Math.max(afterIndex, eventRepository.getSize() - 1);

        log.debug("Poll request: afterIndex={}, limit={}, returned {} events, nextCursor={}",
                afterIndex, limit, events.size(), nextCursor);

        return PollResponse.builder()
                .events(events)
                .nextCursor(nextCursor)
                .epoch(eventRepository.getEpoch())
                .hasMore(nextCursor < eventRepository.getSize() - 1)
                .build();
    }

    /**
     * Get system stats
     */
    public SystemStats getStats() {
        return SystemStats.builder()
                .totalEventsCreated(eventRepository.getTotalCount())
                .eventsInMemory(eventRepository.getSize())
                .build();
    }
}
