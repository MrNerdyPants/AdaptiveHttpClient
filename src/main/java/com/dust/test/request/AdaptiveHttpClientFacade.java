package com.dust.test.request;

import com.google.common.util.concurrent.RateLimiter;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Represents the AdaptiveHttpClientFacade class in the chenosis-bulk-api-calling-service project.
 *
 * @author Kashan Asim
 * @version 1.0
 * @project chenosis-bulk-api-calling-service
 * @module com.mtn.system.services.impl
 * @class AdaptiveHttpClientFacade
 * @lastModifiedBy Kashan.Asim
 * @lastModifiedDate 7/30/2025
 * @license Licensed under the Apache License, Version 2.0
 * @description A brief description of the class functionality.
 * @notes <ul>
 * <li>Provide any additional notes or remarks here.</li>
 * </ul>
 * @since 7/30/2025
 */

public class AdaptiveHttpClientFacade implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveHttpClientFacade.class);

    private final HttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final CircuitBreakerService circuitBreaker;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor executor;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failure = new AtomicLong();
    private final Instant startTime;

    private final double targetTps;
    private final int max_retry = 3;
    private volatile double avgResponseTimeMs = 200; // initial guess

    public AdaptiveHttpClientFacade(double tps) {
        this.targetTps = tps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.rateLimiter = RateLimiter.create(tps);
        this.circuitBreaker = new CircuitBreakerService(5, Duration.ofSeconds(35));
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.executor = new ThreadPoolExecutor(4, 200, 70, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        this.startTime = Instant.now();

        startMetricsLogger();
        startConcurrencyAdjuster();
    }

    public CompletableFuture<AdaptiveHttpResponse> sendGet(String url) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .GET()
                .build(), 0);
    }

    public CompletableFuture<AdaptiveHttpResponse> sendGet(String url, String... headers) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .headers(headers)
                .timeout(Duration.ofSeconds(35))
                .GET()
                .build(), 0);
    }

    public CompletableFuture<AdaptiveHttpResponse> sendPost(String url, String requestBody, String... headers) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .headers(headers)
                .timeout(Duration.ofSeconds(35))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(), 0);
    }

    public boolean isComplete(Long count) {
        return (count == total.get() && success.get() + failure.get() == total.get());
    }

    public CompletableFuture<AdaptiveHttpResponse> send(HttpRequest request, int retry) {
        rateLimiter.acquire();

        if (retry == 0) total.incrementAndGet();

        if (!circuitBreaker.allowRequest()) {
            return CompletableFuture.completedFuture(new AdaptiveHttpResponse("Circuit breaker is open"));
        }

        return CompletableFuture.supplyAsync(() -> {
            Instant start = Instant.now();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long time = Duration.between(start, Instant.now()).toMillis();
                avgResponseTimeMs = (avgResponseTimeMs * 0.8) + (time * 0.2); // EMA smoothing

                if (response.statusCode() < 400) {
                    circuitBreaker.recordSuccess();
                    success.incrementAndGet();
                    return new AdaptiveHttpResponse(response, time);
                } else {
                    circuitBreaker.recordFailure();
                    failure.incrementAndGet();
                    return new AdaptiveHttpResponse("Bad status: " + response.statusCode(), response, time);
                }
            } catch (IOException | InterruptedException e) {
                long time = Duration.between(start, Instant.now()).toMillis();
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();

                avgResponseTimeMs = (avgResponseTimeMs * 0.8) + (time * 0.2);

                throw new CompletionException(e);
            }
        }, executor).handleAsync((result, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(result);
            } else {
                if (retry < max_retry) {
                    logger.warn("Retrying attempt {}/{} after failure: {}", retry + 1, max_retry, ex.getCause().toString());
                    return send(request, retry + 1);
                } else {
                    long failTime = Duration.between(Instant.now(), Instant.now()).toMillis();
                    circuitBreaker.recordFailure();
                    failure.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new AdaptiveHttpResponse("Retry limit reached: " + ex.getCause().getMessage(), failTime)
                    );
                }
            }
        }, executor).thenCompose(Function.identity()); // flatten nested futures
    }

    private void startMetricsLogger() {
        scheduler.scheduleAtFixedRate(() -> {
            Duration uptime = Duration.between(startTime, Instant.now());
            double tps = success.get() / (uptime.getSeconds() > 0 ? (double) uptime.getSeconds() : 1.0);
            logger.info("[TPS: {} | Success: {} | Failed: {} | Total: {} | Active Threads: {} | Pool Size: {} | Uptime: {}s]",
                    String.format("%.2f", tps), success.get(), failure.get(), total.get(),
                    executor.getActiveCount(), executor.getCorePoolSize(), uptime.getSeconds());
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void startConcurrencyAdjuster() {
        scheduler.scheduleAtFixedRate(() -> {
            int optimalThreads = (int) Math.ceil((targetTps * avgResponseTimeMs) / 1000.0);
            optimalThreads = Math.max(1, Math.min(optimalThreads, 200));
            if (optimalThreads != executor.getCorePoolSize()) {
                if (executor.getMaximumPoolSize() < optimalThreads)
                    executor.setMaximumPoolSize(optimalThreads);
                executor.setCorePoolSize(optimalThreads);
                logger.info("Adjusted thread pool size to {} based on avg response time {} ms", optimalThreads, (int) avgResponseTimeMs);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        executor.shutdownNow();
        logger.info("Final Metrics: Success={}, Failed={}, Total={}", success.get(), failure.get(), total.get());
    }

    @Data
    public static class AdaptiveHttpResponse {
        private final boolean successful;
        private final String error;
        private final HttpResponse<String> response;
        private final long executionTimeMs;

        public AdaptiveHttpResponse(HttpResponse<String> response, long time) {
            this.successful = true;
            this.response = response;
            this.error = null;
            this.executionTimeMs = time;
        }

        public AdaptiveHttpResponse(String error) {
            this.successful = false;
            this.response = null;
            this.error = error;
            this.executionTimeMs = 0;
        }

        public AdaptiveHttpResponse(String error, long time) {
            this.successful = false;
            this.response = null;
            this.error = error;
            this.executionTimeMs = time;
        }

        public AdaptiveHttpResponse(String error, HttpResponse<String> response, long time) {
            this.successful = false;
            this.response = response;
            this.error = error;
            this.executionTimeMs = time;
        }
    }

    public static class CircuitBreakerService {
        private enum State {CLOSED, OPEN, HALF_OPEN}

        private State state = State.CLOSED;
        private final int maxFailures;
        private final Duration timeout;
        private final AtomicLong failures = new AtomicLong(0);
        private Instant lastFailure = Instant.now();

        public CircuitBreakerService(int maxFailures, Duration timeout) {
            this.maxFailures = maxFailures;
            this.timeout = timeout;
        }

        public boolean allowRequest() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN && Duration.between(lastFailure, Instant.now()).compareTo(timeout) > 0) {
                state = State.HALF_OPEN;
                return true;
            }
            return state == State.HALF_OPEN;
        }

        public void recordSuccess() {
            failures.set(0);
            state = State.CLOSED;
        }

        public void recordFailure() {
            failures.incrementAndGet();
            lastFailure = Instant.now();
            if (failures.get() >= maxFailures) {
                state = State.OPEN;
                logger.warn("Circuit opened after {} failures", failures.get());
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        double targetTps = 1500.0; // Target transactions per second
        int totalRequests = 10000;

        try (AdaptiveHttpClientFacade client = new AdaptiveHttpClientFacade(targetTps)) {
            for (int i = 0; i < totalRequests; i++) {
                int requestId = i;
                client.sendGet("http://localhost:9090/api/delay/0/" + i) // Simulates ~300ms latency
                        .thenAccept(response -> {
                            if (response.isSuccessful()) {
                                System.out.println("✅ [#" + requestId + "] Success in " + response.getExecutionTimeMs() + "ms at " + LocalDateTime.now());
                            } else {
                                System.err.println("❌ [#" + requestId + "] Failed: " + response.getError());
                            }
                        });
            }

            // Let it run for 60 seconds before shutdown to allow for retries and thread scaling
            Thread.sleep(60_000);
        }

        System.out.println("Test completed.");
    }
}


