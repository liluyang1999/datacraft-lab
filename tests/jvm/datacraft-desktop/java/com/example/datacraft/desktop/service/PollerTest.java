package com.example.datacraft.desktop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PollerTest {

  private static final Duration INTERVAL = Duration.ofSeconds(5);

  private final ManualTicker ticker = new ManualTicker();
  private final List<CompletableFuture<String>> requests = new ArrayList<>();
  private final List<String> results = new ArrayList<>();
  private final List<Throwable> failures = new ArrayList<>();

  private Poller<String> poller() {
    return new Poller<>(
        ticker,
        () -> {
          CompletableFuture<String> request = new CompletableFuture<>();
          requests.add(request);
          return request;
        },
        results::add,
        failures::add,
        () -> INTERVAL);
  }

  @Test
  void fetchesAtOnceAndThenAtTheInterval() {
    Poller<String> poller = poller();
    poller.start();
    ticker.advance(Duration.ZERO);
    assertEquals(1, requests.size());
    requests.getFirst().complete("first");
    assertEquals(List.of("first"), results);
    assertEquals(List.of(INTERVAL), ticker.pending());
    ticker.advance(INTERVAL);
    assertEquals(2, requests.size());
  }

  @Test
  void failuresBackOffUpToThirtySecondsAndASuccessResetsTheInterval() {
    Poller<String> poller = poller();
    poller.start();
    List<Duration> delays = new ArrayList<>();
    for (int attempt = 0; attempt < 5; attempt++) {
      ticker.advance(attempt == 0 ? Duration.ZERO : delays.getLast());
      requests.getLast().completeExceptionally(new IllegalStateException("down"));
      delays.add(ticker.pending().getFirst());
    }
    assertEquals(
        List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(30),
            Duration.ofSeconds(30)),
        delays);
    assertEquals(5, failures.size());
    ticker.advance(delays.getLast());
    requests.getLast().complete("back");
    assertEquals(List.of(INTERVAL), ticker.pending());
  }

  @Test
  void aRefreshWhileARequestIsInFlightDoesNotStartAnother() {
    Poller<String> poller = poller();
    poller.start();
    ticker.advance(Duration.ZERO);
    poller.refresh();
    ticker.advance(Duration.ofSeconds(60));
    assertEquals(1, requests.size(), "one request in flight at a time");
    requests.getFirst().complete("done");
    poller.refresh();
    ticker.advance(Duration.ZERO);
    assertEquals(2, requests.size(), "a refresh after it fetches at once");
  }

  @Test
  void theResultOfARequestStartedBeforeStopIsDropped() {
    Poller<String> poller = poller();
    poller.start();
    ticker.advance(Duration.ZERO);
    poller.stop();
    requests.getFirst().complete("stale");
    assertTrue(results.isEmpty());
    assertTrue(ticker.pending().isEmpty(), "nothing is scheduled after stop");
    assertFalse(poller.running());
  }

  @Test
  void restartForgetsFailuresAndFetchesAgain() {
    Poller<String> poller = poller();
    poller.start();
    ticker.advance(Duration.ZERO);
    requests.getFirst().completeExceptionally(new IllegalStateException("down"));
    ticker.advance(INTERVAL);
    requests.get(1).completeExceptionally(new IllegalStateException("down"));
    assertEquals(List.of(Duration.ofSeconds(10)), ticker.pending());
    poller.restart();
    ticker.advance(Duration.ZERO);
    assertEquals(3, requests.size());
    requests.get(2).completeExceptionally(new IllegalStateException("down"));
    assertEquals(List.of(INTERVAL), ticker.pending(), "the backoff starts from the base again");
  }

  @Test
  void aFetchThatThrowsCountsAsAFailure() {
    Poller<String> poller =
        new Poller<>(
            ticker,
            () -> {
              throw new IllegalStateException("bad");
            },
            results::add,
            failures::add,
            () -> INTERVAL);
    poller.start();
    ticker.advance(Duration.ZERO);
    assertEquals(1, failures.size());
    assertEquals(List.of(INTERVAL), ticker.pending());
  }

  @Test
  void backoffIsTheBaseTimesTwoToTheFailuresMinusOneAndCapped() {
    assertEquals(INTERVAL, Poller.backoff(INTERVAL, 0));
    assertEquals(INTERVAL, Poller.backoff(INTERVAL, 1));
    assertEquals(Duration.ofSeconds(20), Poller.backoff(INTERVAL, 3));
    assertEquals(Poller.MAX_BACKOFF, Poller.backoff(INTERVAL, 100));
  }
}
