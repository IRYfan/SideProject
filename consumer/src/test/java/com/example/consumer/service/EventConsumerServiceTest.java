package com.example.consumer.service;

import com.example.consumer.model.Event;
import com.example.consumer.model.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EventConsumerService
 * Uses Mockito to mock RestTemplate
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventConsumerService Unit Tests")
class EventConsumerServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private EventConsumerService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new EventConsumerService(restTemplate);

        // Set test configuration
        ReflectionTestUtils.setField(service, "producerUrl", "http://localhost:8080");

        // Use temporary directory for cursor file
        String cursorFile = tempDir.resolve("test-cursor.txt").toString();
        ReflectionTestUtils.setField(service, "cursorFilePath", cursorFile);

        // Initialize cursor
        service.initCursor();
    }

    @Test
    @DisplayName("pollOnce: Should poll and process events")
    void shouldPollAndProcessEvents() {
        // Given
        Event event1 = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        Event event2 = createEvent(EventType.ENQUEUED, "queue-2", "agent-2");

        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event1, event2));
        mockResponse.setNextCursor(5);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then
        assertThat(service.getTotalConsumed()).isEqualTo(2);
        assertThat(service.getLastCursor()).isEqualTo(5);
        assertThat(service.getQueueCount("queue-1")).isEqualTo(1);
        assertThat(service.getQueueCount("queue-2")).isEqualTo(1);

        verify(restTemplate, times(1)).getForObject(anyString(), eq(PollResponse.class));
    }

    @Test
    @DisplayName("pollOnce: Should handle empty response gracefully")
    void shouldHandleEmptyResponse() {
        // Given
        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of());
        mockResponse.setNextCursor(0);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then
        assertThat(service.getTotalConsumed()).isZero();
        assertThat(service.getLastCursor()).isEqualTo(-1); // Cursor should not be updated
    }

    @Test
    @DisplayName("pollOnce: Should handle null response gracefully")
    void shouldHandleNullResponse() {
        // Given
        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(null);

        // When
        service.pollOnce();

        // Then
        assertThat(service.getTotalConsumed()).isZero();
    }

    @Test
    @DisplayName("processEvent: ENQUEUED should increment count")
    void shouldIncrementCountForEnqueuedEvent() {
        // Given
        Event event = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event));
        mockResponse.setNextCursor(1);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then
        assertThat(service.getQueueCount("queue-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("processEvent: DEQUEUED should decrement count")
    void shouldDecrementCountForDequeuedEvent() {
        // Given: first enqueue 2, then dequeue 1
        Event event1 = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        Event event2 = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        Event event3 = createEvent(EventType.DEQUEUED, "queue-1", "agent-1");

        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event1, event2, event3));
        mockResponse.setNextCursor(3);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then: 2 enqueued - 1 dequeued = 1
        assertThat(service.getQueueCount("queue-1")).isEqualTo(1);
        assertThat(service.getTotalConsumed()).isEqualTo(3);
    }

    @Test
    @DisplayName("getQueueMetrics: Should return all queue metrics")
    void shouldReturnAllQueueMetrics() {
        // Given
        Event event1 = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        Event event2 = createEvent(EventType.ENQUEUED, "queue-2", "agent-2");
        Event event3 = createEvent(EventType.ENQUEUED, "queue-3", "agent-3");

        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event1, event2, event3));
        mockResponse.setNextCursor(3);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        service.pollOnce();

        // When
        Map<String, Integer> metrics = service.getQueueMetrics();

        // Then
        assertThat(metrics).hasSize(3);
        assertThat(metrics.get("queue-1")).isEqualTo(1);
        assertThat(metrics.get("queue-2")).isEqualTo(1);
        assertThat(metrics.get("queue-3")).isEqualTo(1);
    }

    @Test
    @DisplayName("getQueueCount: Non-existent queue should return 0")
    void shouldReturnZeroForNonExistentQueue() {
        // When
        int count = service.getQueueCount("non-existent-queue");

        // Then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Cursor persistence: Should save and load cursor")
    void shouldPersistAndLoadCursor() throws Exception {
        // Given
        Event event = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event));
        mockResponse.setNextCursor(42);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then: cursor should be saved to file
        String cursorFilePath = (String) ReflectionTestUtils.getField(service, "cursorFilePath");
        Path cursorFile = Path.of(cursorFilePath);
        assertThat(Files.exists(cursorFile)).isTrue();
        String content = Files.readString(cursorFile);
        assertThat(content).isEqualTo("42");

        // Create new service instance to test loading
        EventConsumerService newService = new EventConsumerService(restTemplate);
        ReflectionTestUtils.setField(newService, "producerUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(newService, "cursorFilePath", cursorFilePath);
        newService.initCursor();

        // Should load previously saved cursor
        assertThat(newService.getLastCursor()).isEqualTo(42);
    }

    @Test
    @DisplayName("Exception handling: Should not crash on network error")
    void shouldHandleNetworkError() {
        // Given
        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenThrow(new RuntimeException("Network error"));

        // When
        service.pollOnce();

        // Then: should not crash
        assertThat(service.getTotalConsumed()).isZero();
    }

    @Test
    @DisplayName("Should calculate processing lag")
    void shouldCalculateProcessingLag() {
        // Given: event timestamp is 5 seconds ago
        Event event = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        event.setTimestamp(Instant.now().minusSeconds(5)); // Event from 5 seconds ago

        PollResponse mockResponse = new PollResponse();
        mockResponse.setEvents(List.of(event));
        mockResponse.setNextCursor(1);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(mockResponse);

        // When
        service.pollOnce();

        // Then: lastLagMillis should be greater than 0
        assertThat(service.getLastLagMillis()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Multiple polls: Cursor should increment across polls")
    void shouldIncrementCursorAcrossMultiplePolls() {
        // Given: first poll
        Event event1 = createEvent(EventType.ENQUEUED, "queue-1", "agent-1");
        PollResponse response1 = new PollResponse();
        response1.setEvents(List.of(event1));
        response1.setNextCursor(1);

        // Second poll
        Event event2 = createEvent(EventType.ENQUEUED, "queue-2", "agent-2");
        PollResponse response2 = new PollResponse();
        response2.setEvents(List.of(event2));
        response2.setNextCursor(2);

        when(restTemplate.getForObject(anyString(), eq(PollResponse.class)))
                .thenReturn(response1)
                .thenReturn(response2);

        // When
        service.pollOnce();
        assertThat(service.getLastCursor()).isEqualTo(1);

        service.pollOnce();
        assertThat(service.getLastCursor()).isEqualTo(2);

        // Then
        assertThat(service.getTotalConsumed()).isEqualTo(2);
        verify(restTemplate, times(2)).getForObject(anyString(), eq(PollResponse.class));
    }

    // Helper method
    private Event createEvent(EventType type, String queueId, String agentId) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID().toString());
        event.setTimestamp(Instant.now());
        event.setEventType(type);
        event.setQueueId(queueId);
        event.setAgentId(agentId);
        return event;
    }
}
