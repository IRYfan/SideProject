package com.learning.producer.service;

import com.learning.producer.model.Event;
import com.learning.producer.model.EventType;
import com.learning.producer.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EventService
 * Uses Mockito to mock Repository layer
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventService Unit Tests")
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("createEvent: Should create event and save to repository")
    void shouldCreateAndSaveEvent() {
        // Given
        EventType eventType = EventType.ENQUEUED;
        String queueId = "queue-1";
        String agentId = "agent-123";

        // When
        Event result = eventService.createEvent(eventType, queueId, agentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getEventType()).isEqualTo(eventType);
        assertThat(result.getQueueId()).isEqualTo(queueId);
        assertThat(result.getAgentId()).isEqualTo(agentId);

        // Verify repository.add was called once
        verify(eventRepository, times(1)).add(any(Event.class));
    }

    @Test
    @DisplayName("pollEvents: Should return events with correct cursor")
    void shouldPollEventsWithCorrectCursor() {
        // Given
        int afterIndex = 0;
        int limit = 10;
        List<Event> mockEvents = Arrays.asList(
                Event.create(EventType.ENQUEUED, "queue-1", "agent-1"),
                Event.create(EventType.DEQUEUED, "queue-2", "agent-2")
        );

        when(eventRepository.getAfter(eq(afterIndex), eq(limit))).thenReturn(mockEvents);
        when(eventRepository.getSize()).thenReturn(5);
        when(eventRepository.getEpoch()).thenReturn("epoch-1");

        // When
        PollResponse response = eventService.pollEvents(afterIndex, limit);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getEvents()).hasSize(2);
        assertThat(response.getNextCursor()).isEqualTo(4); // size - 1
        assertThat(response.getEpoch()).isEqualTo("epoch-1");
        assertThat(response.isHasMore()).isFalse();

        verify(eventRepository, times(1)).getAfter(eq(afterIndex), eq(limit));
        verify(eventRepository, times(2)).getSize();
        verify(eventRepository, times(1)).getEpoch();
    }

    @Test
    @DisplayName("pollEvents: Should return empty list when no events available")
    void shouldReturnEmptyListWhenNoEventsAvailable() {
        // Given
        when(eventRepository.getAfter(anyInt(), anyInt())).thenReturn(List.of());
        when(eventRepository.getSize()).thenReturn(0);
        when(eventRepository.getEpoch()).thenReturn("epoch-1");

        // When
        PollResponse response = eventService.pollEvents(10, 10);

        // Then
        assertThat(response.getEvents()).isEmpty();
        assertThat(response.getNextCursor()).isEqualTo(10);
        assertThat(response.getEpoch()).isEqualTo("epoch-1");
    }

    @Test
    @DisplayName("pollEvents: Should calculate hasMore flag correctly")
    void shouldCalculateHasMoreCorrectly() {
        // Given: 11 events, polling with afterIndex=4
        when(eventRepository.getAfter(eq(4), eq(5))).thenReturn(List.of(
                Event.create(EventType.ENQUEUED, "q1", "a1"),
                Event.create(EventType.ENQUEUED, "q2", "a2"),
                Event.create(EventType.ENQUEUED, "q3", "a3"),
                Event.create(EventType.ENQUEUED, "q4", "a4"),
                Event.create(EventType.ENQUEUED, "q5", "a5")
        ));
        when(eventRepository.getSize()).thenReturn(11);
        when(eventRepository.getEpoch()).thenReturn("epoch-1");

        // When: poll from index 4
        PollResponse response = eventService.pollEvents(4, 5);

        // Then: nextCursor = max(4, 11-1) = 10, hasMore = 10 < 10 = false
        // Already at the end
        assertThat(response.getNextCursor()).isEqualTo(10);
        assertThat(response.getEpoch()).isEqualTo("epoch-1");
        assertThat(response.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("getStats: Should return system statistics")
    void shouldReturnSystemStats() {
        // Given
        when(eventRepository.getTotalCount()).thenReturn(100L);
        when(eventRepository.getSize()).thenReturn(95);

        // When
        SystemStats stats = eventService.getStats();

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.getTotalEventsCreated()).isEqualTo(100L);
        assertThat(stats.getEventsInMemory()).isEqualTo(95);

        verify(eventRepository, times(1)).getTotalCount();
        verify(eventRepository, times(1)).getSize();
    }

    @Test
    @DisplayName("createEvent: Should create events for different event types")
    void shouldCreateEventsForDifferentTypes() {
        // Test ENQUEUED
        Event enqueuedEvent = eventService.createEvent(EventType.ENQUEUED, "q1", "a1");
        assertThat(enqueuedEvent.getEventType()).isEqualTo(EventType.ENQUEUED);

        // Test DEQUEUED
        Event dequeuedEvent = eventService.createEvent(EventType.DEQUEUED, "q2", "a2");
        assertThat(dequeuedEvent.getEventType()).isEqualTo(EventType.DEQUEUED);

        verify(eventRepository, times(2)).add(any(Event.class));
    }
}
