package com.processVisualisation.virtualKitchen.common.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Connection pool and timeout settings for the MongoDB driver ({@code app.mongo.*}). The driver's defaults never time
 * out a read on a stalled connection (socket timeout 0), let a request wait two minutes for a pooled connection, and
 * keep no warm connections, so the first requests after a quiet period pay for new TLS handshakes to Atlas.
 * <ul>
 *   <li>{@code socket-timeout-ms} (60 s): the longest any single database operation may take before it fails, so a
 *       stuck connection can't hold a request thread forever. Above the slowest legitimate query (admin analytics).</li>
 *   <li>{@code max-wait-ms} (10 s): how long a request waits for a free connection before failing fast.</li>
 *   <li>{@code min-pool-size} (5), {@code max-pool-size} (100), {@code max-idle-ms} (10 min): warm connections kept
 *       ready, the upper bound, and when idle ones are recycled.</li>
 * </ul>
 * These are applied after the connection string, so they win over the same options written in {@code MONGODB_URI}.
 */
@Configuration
public class MongoClientTuningConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer poolAndTimeoutSettings(
            @Value("${app.mongo.socket-timeout-ms:60000}") int socketTimeoutMs,
            @Value("${app.mongo.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.mongo.max-wait-ms:10000}") long maxWaitMs,
            @Value("${app.mongo.min-pool-size:5}") int minPoolSize,
            @Value("${app.mongo.max-pool-size:100}") int maxPoolSize,
            @Value("${app.mongo.max-idle-ms:600000}") long maxIdleMs) {
        return builder -> builder
                .applyToSocketSettings(socket -> socket
                        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
                        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool
                        .minSize(minPoolSize)
                        .maxSize(maxPoolSize)
                        .maxWaitTime(maxWaitMs, TimeUnit.MILLISECONDS)
                        .maxConnectionIdleTime(maxIdleMs, TimeUnit.MILLISECONDS));
    }
}
