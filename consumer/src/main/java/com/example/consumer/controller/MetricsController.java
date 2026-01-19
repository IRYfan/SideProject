package com.example.consumer.controller;

import com.example.consumer.service.EventConsumerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Metrics API controller for consumer service
 * Exposes aggregated metrics
 */
@Slf4j
@RestController
@RequestMapping("/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final EventConsumerService eventConsumerService;

    /**
     * Get metrics for a specific queue
     * GET /v1/metrics/queues/{queueId}
     */
    @GetMapping("/queues/{queueId}")
    public ResponseEntity<Map<String, Object>> getQueueMetric(@PathVariable String queueId) {
        int count = eventConsumerService.getQueueCount(queueId);
        Map<String, Object> response = new HashMap<>();
        response.put("queueId", queueId);
        response.put("waitingCount", count);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all queue metrics
     * GET /v1/metrics/queues
     */
    @GetMapping("/queues")
    public ResponseEntity<Map<String, Object>> getAllQueueMetrics() {
        Map<String, Object> response = new HashMap<>();
        response.put("queues", eventConsumerService.getQueueMetrics());
        response.put("totalConsumed", eventConsumerService.getTotalConsumed());
        response.put("lastCursor", eventConsumerService.getLastCursor());
        response.put("lastLagMs", eventConsumerService.getLastLagMillis());
        response.put("epoch", eventConsumerService.getEpoch());
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }

    /**
     * Health check
     * GET /v1/metrics/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
