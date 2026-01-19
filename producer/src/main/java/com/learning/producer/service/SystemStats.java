package com.learning.producer.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * System statistics model
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemStats {

    /**
     * Total events created since service start
     */
    private long totalEventsCreated;

    /**
     * Events currently in memory
     */
    private int eventsInMemory;
}
