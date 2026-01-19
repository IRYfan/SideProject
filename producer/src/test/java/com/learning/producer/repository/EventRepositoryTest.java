package com.learning.producer.repository;

import com.learning.producer.model.Event;
import com.learning.producer.model.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EventRepository
 * Tests core functionality of in-memory event storage
 */
@DisplayName("EventRepository Unit Tests")
class EventRepositoryTest {

    private EventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new EventRepository();
    }

    @Test
    @DisplayName("Should add event and increment counter")
    void shouldAddEventAndIncrementCounter() {
        // Given
        Event event = Event.create(EventType.ENQUEUED, "queue-1", "agent-1");

        // When
        repository.add(event);

        // Then
        assertThat(repository.getSize()).isEqualTo(1);
        assertThat(repository.getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should add multiple events")
    void shouldAddMultipleEvents() {
        // Given
        Event event1 = Event.create(EventType.ENQUEUED, "queue-1", "agent-1");
        Event event2 = Event.create(EventType.DEQUEUED, "queue-2", "agent-2");
        Event event3 = Event.create(EventType.ENQUEUED, "queue-3", "agent-3");

        // When
        repository.add(event1);
        repository.add(event2);
        repository.add(event3);

        // Then
        assertThat(repository.getSize()).isEqualTo(3);
        assertThat(repository.getTotalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getAfter: Should get events after index 0")
    void shouldGetEventsAfterIndex() {
        // Given: add 3 events
        repository.add(Event.create(EventType.ENQUEUED, "queue-1", "agent-1"));
        repository.add(Event.create(EventType.DEQUEUED, "queue-2", "agent-2"));
        repository.add(Event.create(EventType.ENQUEUED, "queue-3", "agent-3"));

        // When: get events after index 0
        List<Event> events = repository.getAfter(0, 10);

        // Then: should return events at index 1 and 2
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("getAfter: Should respect limit parameter")
    void shouldRespectLimitParameter() {
        // Given: add 5 events
        for (int i = 0; i < 5; i++) {
            repository.add(Event.create(EventType.ENQUEUED, "queue-" + i, "agent-" + i));
        }

        // When: get only 2 events
        List<Event> events = repository.getAfter(-1, 2);

        // Then
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("getAfter: Should return empty list when after index is out of bounds")
    void shouldReturnEmptyListWhenAfterIndexIsOutOfBounds() {
        // Given: only 2 events
        repository.add(Event.create(EventType.ENQUEUED, "queue-1", "agent-1"));
        repository.add(Event.create(EventType.DEQUEUED, "queue-2", "agent-2"));

        // When: request events after index 10
        List<Event> events = repository.getAfter(10, 10);

        // Then
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("getAfter: Should get all events when starting from -1")
    void shouldGetAllEventsWhenStartingFromMinusOne() {
        // Given: add 3 events
        repository.add(Event.create(EventType.ENQUEUED, "queue-1", "agent-1"));
        repository.add(Event.create(EventType.DEQUEUED, "queue-2", "agent-2"));
        repository.add(Event.create(EventType.ENQUEUED, "queue-3", "agent-3"));

        // When: start from -1
        List<Event> events = repository.getAfter(-1, 100);

        // Then: should return all 3 events
        assertThat(events).hasSize(3);
    }

    @Test
    @DisplayName("Should be thread-safe when adding events")
    void shouldBeThreadSafeWhenAddingEvents() throws InterruptedException {
        // Given: 10 threads adding events concurrently
        int threadCount = 10;
        int eventsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        // When
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    repository.add(Event.create(EventType.ENQUEUED, "queue-1", "agent-1"));
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then: should have 1000 events
        assertThat(repository.getSize()).isEqualTo(threadCount * eventsPerThread);
        assertThat(repository.getTotalCount()).isEqualTo(threadCount * eventsPerThread);
    }

    @Test
    @DisplayName("Should return zero for empty repository")
    void shouldReturnZeroForEmptyRepository() {
        // When & Then
        assertThat(repository.getSize()).isZero();
        assertThat(repository.getTotalCount()).isZero();
    }
}
