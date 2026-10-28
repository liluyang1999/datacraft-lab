package com.example.datacraft.desktop.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class HttpTransportTest {
  @Test
  void anUnusedTransportDoesNotInitializeNetworkingEvenWhenClosed() {
    AtomicInteger created = new AtomicInteger();
    HttpTransport transport =
        new HttpTransport(
            executor -> {
              created.incrementAndGet();
              return HttpClient.newBuilder().executor(executor).build();
            });
    assertEquals(1, transport.json().createObjectNode().put("value", 1).get("value").asInt());
    assertEquals(0, created.get());
    transport.close();
    transport.close();
    var failure =
        assertThrows(
            CompletionException.class,
            () ->
                transport
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:1")).build())
                    .join());
    assertTrue(failure.getCause() instanceof ApiException);
    assertEquals(0, created.get());
  }

  @Test
  void firstRequestsShareInitializationAndCancellationDoesNotSendALateRequest() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    try (StubServer server = new StubServer();
        HttpTransport transport =
            new HttpTransport(
                executor -> {
                  created.incrementAndGet();
                  entered.countDown();
                  await(release);
                  return HttpClient.newBuilder().executor(executor).build();
                })) {
      server.on("GET /health", 200, "{}");
      var request = transport.request(server.uri(), "/health", Duration.ofSeconds(5)).build();
      var first = transport.send(request);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var second = transport.send(request);
      var cancelled = transport.send(request);
      assertTrue(cancelled.cancel(true));
      release.countDown();
      assertEquals(200, first.get(5, TimeUnit.SECONDS).status());
      assertEquals(200, second.get(5, TimeUnit.SECONDS).status());
      assertEquals(1, created.get());
      assertEquals(2, server.requests().size());
    } finally {
      release.countDown();
    }
  }

  @Test
  void closingDuringInitializationRejectsRequestsAndClosesTheLateClient() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<HttpClient> built = new CompletableFuture<>();
    HttpTransport transport =
        new HttpTransport(
            executor -> {
              HttpClient client = HttpClient.newBuilder().executor(executor).build();
              built.complete(client);
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return client;
            });
    try (StubServer server = new StubServer()) {
      var request = transport.request(server.uri(), "/health", Duration.ofSeconds(5)).build();
      var pending = transport.send(request);
      HttpClient client = built.get(5, TimeUnit.SECONDS);
      transport.close();
      release.countDown();
      assertThrows(CompletionException.class, pending::join);
      assertTrue(client.awaitTermination(Duration.ofSeconds(5)));
      assertTrue(server.requests().isEmpty());
    } finally {
      release.countDown();
      transport.close();
    }
  }

  @Test
  void initializationFailureCompletesEveryRequestInsteadOfLeavingItPending() {
    AtomicInteger created = new AtomicInteger();
    try (HttpTransport transport =
        new HttpTransport(
            executor -> {
              created.incrementAndGet();
              throw new IllegalStateException("initialization failed");
            })) {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1")).build();
      for (int i = 0; i < 2; i++) {
        var failure = assertThrows(CompletionException.class, () -> transport.send(request).join());
        assertTrue(failure.getCause() instanceof ApiException);
      }
      assertEquals(1, created.get());
    }
  }

  @Test
  void theRequestDeadlineIncludesInitializationAndDoesNotSendAnExpiredRequest() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (StubServer server = new StubServer();
        HttpTransport transport =
            new HttpTransport(
                executor -> {
                  await(release);
                  return HttpClient.newBuilder().executor(executor).build();
                })) {
      server.on("GET /health", 200, "{}");
      var timed =
          transport.send(transport.request(server.uri(), "/health", Duration.ofMillis(50)).build());
      var failure = assertThrows(ExecutionException.class, () -> timed.get(2, TimeUnit.SECONDS));
      assertEquals(ApiError.TIMED_OUT, ApiException.of(failure).orElseThrow().error());
      release.countDown();
      var next =
          transport.send(transport.request(server.uri(), "/health", Duration.ofSeconds(5)).build());
      assertEquals(200, next.get(5, TimeUnit.SECONDS).status());
      assertEquals(1, server.requests().size());
    } finally {
      release.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
