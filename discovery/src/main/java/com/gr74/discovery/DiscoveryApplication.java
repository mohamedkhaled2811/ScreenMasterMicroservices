package com.gr74.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Netflix Eureka <b>server</b> — the service registry for the whole system.
 *
 * <p>Every other service runs a Eureka <i>client</i> and registers here on startup; the gateway
 * resolves {@code lb://catalog}, {@code lb://booking}, … through this registry and load-balances
 * across the healthy instances it reports. See {@code docs/concepts/service-discovery.md}.
 *
 * <p>{@code @EnableEurekaServer} is what flips this from "a Eureka client" to "the registry
 * itself" — it pulls in the dashboard (port 8761) and the Eureka REST API. The companion
 * {@code application.yml} tells this node <i>not</i> to register with or fetch from itself
 * (it has no peers in this single-node lab).
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }
}
