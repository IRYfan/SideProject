package com.learning.producer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Basic application tests - verifies Spring context can load properly
 */
@SpringBootTest
@DisplayName("Producer Application Basic Tests")
class ProducerApplicationTests {

    @Test
    @DisplayName("Application context should load successfully")
    void contextLoads() {
        // Test passes if context loads successfully
    }
}
