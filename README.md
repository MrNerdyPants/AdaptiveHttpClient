---

# 🚀 Building a Smart HTTP Client in Java: Dynamic Thread Management with Rate Limiting & Circuit Breaker

📌 *By Kashan Asim | Aug 2025*
🔗 [GitHub Repository](https://github.com/MrNerdyPants/AdaptiveHttpClient)

---

In my recent work with a bulk API calling system, I had to design an HTTP client that could hit downstream services at a desired **input TPS (transactions per second)**, dynamically adapt to runtime latency or congestion, and avoid overwhelming downstream services.

Initially, the system either over-flooded the service with threads or hung when the server started lagging. So, I decided to go beyond traditional approaches like `RestTemplate` or `WebClient` with fixed thread pools and implement a **self-aware HTTP client**.

In this article, I’ll walk you through:

1. The **problem** with fixed threads in bulk processing
2. The **rate-limited, circuit-breaker-enabled HTTP client**
3. A smart **thread adjustment algorithm**
4. A **realistic delay test service**
5. Real-world benefits, and a known limitation we’ll explore in a future article.

---

## 💡 The Problem: Threads Gone Wild

When firing thousands of requests per minute, using a static thread pool creates two extremes:

* If the thread pool is **too small**, you won’t meet your TPS targets.
* If the thread pool is **too big**, you’ll saturate downstream systems, or worse — overload the JVM with blocking threads.

What we needed was an **adaptive mechanism** that starts with a baseline thread count and **dynamically scales up/down** based on how quickly requests complete.

---

## 🧠 The Solution: Dynamic Threaded HTTP Client

Let’s break it down into its major components:

---

### 1️⃣ Circuit Breaker & Rate Limiter

```java
RateLimiter rateLimiter = RateLimiter.create(targetTPS); // Guava's RateLimiter

if (!circuitBreaker.allowRequest()) {
    return CompletableFuture.completedFuture(
        new AdaptiveHttpResponse("Circuit breaker is open")
    );
}

rateLimiter.acquire(); // Enforce TPS
```

This makes sure:

* We **never exceed our desired TPS**
* We **skip sending** requests when the system is unstable

---

### 2️⃣ Submitting Requests with Monitoring

```java
CompletableFuture<AdaptiveHttpResponse> send(HttpRequest request, int retry) {
    rateLimiter.acquire();
    if (retry == 0) total.incrementAndGet();

    return CompletableFuture.supplyAsync(() -> {
        Instant start = Instant.now();
        try {
            // Simulate a call to downstream service
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return new AdaptiveHttpResponse(response.body());
        } catch (Exception ex) {
            // Retry logic, error tracking etc.
        } finally {
            updateCompletionStats(Duration.between(start, Instant.now()).toMillis());
        }
    }, executor);
}
```

We track **latency** and adjust thread counts accordingly.

---

### 3️⃣ Dynamic Thread Tuner

We run a scheduler every 15 seconds to **check current TPS** and **adjust thread count**.

```java
int delta = actualTPS - targetTPS;

if (delta < 0) {
    currentThreads += Math.min(Math.abs(delta), maxThreads - currentThreads);
} else {
    currentThreads = Math.max(minThreads, currentThreads - (delta / 2));
}

((ThreadPoolExecutor) executor).setCorePoolSize(currentThreads);
((ThreadPoolExecutor) executor).setMaximumPoolSize(currentThreads);
```

This ensures:

* We **scale up** threads if we’re not hitting target TPS
* We **scale down** if we’re overshooting and overloading downstream

---

## 🧪 The Test: Simulated Delay Service

I created a quick Spring Boot controller to simulate downstream latency and spike scenarios.

```java
@GetMapping("/{delay}/{count}")
ResponseEntity getDelay(@PathVariable int delay, @PathVariable int count) throws InterruptedException {
    Thread.sleep(delay * 1000);

    if (Math.random() > 0.8)
        Thread.sleep(40000); // Random spike

    System.out.println("Request received!\t" + count);
    return ResponseEntity.ok(count);
}
```

✅ This helped verify how well the HTTP client adjusts to changing server responsiveness.

---

## 📈 Example Scenario

Let’s say we want to achieve **100 TPS**:

* We start with, say, **20 threads**.
* Our scheduler observes we’re only achieving **65 TPS**.
* Based on this delta (35 short), we scale threads up to **40**.
* On the next run, we hit 102 TPS. Great.
* But if the downstream becomes slow, we scale back to avoid queuing delays or JVM lock-ups.

This loop continues, always aiming to **hover around target TPS**, avoiding saturation.

---

## 🌍 Real-World Benefits

* **SLA Adherence**: Meet your client TPS/SLA requirements more reliably.
* **Resource Efficient**: Prevent over-provisioning of threads and CPU usage.
* **Backpressure-Aware**: Reacts to latency spikes instead of pushing harder.
* **Auto-Tuning**: Minimal human intervention or redeploys for tuning thread count.

---

## ⚠️ A Drawback to Address

One limitation in the current implementation is the **blind ramp-up/down logic**. It assumes all request failures are performance-related, which isn't always the case. Also, it does **not differentiate** between client-side timeouts vs. server-side issues vs. network glitches.

> 💡 *In a future article, I’ll improve this model by adding latency buckets, error classification, and a sliding window TPS calculator for smarter decisions.* Stay tuned!

---

## 🧩 Final Thoughts

This smart HTTP client isn't just a tool — it’s a **strategy** for making high-volume services reliable, predictable, and efficient.

Feel free to contribute, fork, or follow the repo here:
🔗 [GitHub Repository](https://github.com/MrNerdyPants/AdaptiveHttpClient)

---

## ✍️ About the Author

**Kashan Asim** is a Senior Java Developer and systems engineer who builds scalable backend systems, telecom integrations, and dynamic optimization algorithms. [Connect on LinkedIn](https://www.linkedin.com/in/k%C4%81sh%C4%81n-asim-7a813a174/)

---

