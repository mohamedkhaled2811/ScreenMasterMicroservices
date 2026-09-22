package com.gr74.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Smoke test: catalog context wires up (H2, no Eureka). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogApplicationTests {

    @Test
    void contextLoads() {
        // If the context fails to start, this test fails — that's the assertion.
    }
}
