package com.example.datacraft.desktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.datacraft.desktop.service.Messages;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class FormatsTest {

  @Test
  void durationsReadInTheirLargestUsefulUnit() {
    assertEquals("0.4 s", Formats.duration(Duration.ofMillis(400)));
    assertEquals("1.2 s", Formats.duration(Duration.ofMillis(1234)));
    assertEquals("48 s", Formats.duration(Duration.ofSeconds(48)));
    assertEquals("3 min 05 s", Formats.duration(Duration.ofSeconds(185)));
    assertEquals("2 h 07 min", Formats.duration(Duration.ofMinutes(127)));
    assertEquals("0.0 s", Formats.duration(Duration.ofMillis(-5)));
  }

  @Test
  void sizesAndCounts() {
    assertEquals("512 B", Formats.bytes(512));
    assertEquals("1.5 KB", Formats.bytes(1536));
    assertEquals("64 MB", Formats.bytes(64L * 1024 * 1024));
    assertEquals("1,234,567", Formats.thousands(1_234_567));
    assertEquals("12 ms", Formats.latency(Duration.ofMillis(12)));
  }

  @Test
  void metricsAreFormattedByTheirKey() {
    assertEquals("1.2 s", Formats.metric("durationMillis", "1234"));
    assertEquals("2.0 KB", Formats.metric("bytes", "2048"));
    assertEquals("12,000", Formats.metric("rows", "12000"));
    assertEquals("abc", Formats.metric("rows", "abc"));
    assertEquals("3.5.1", Formats.metric("sparkVersion", "3.5.1"));
  }

  @Test
  void timesAreRelativeOrOnTheClock() {
    Instant now = Instant.parse("2026-10-27T10:00:00Z");
    assertEquals("刚刚", Formats.ago(now.minusSeconds(2), now));
    assertEquals("30 秒前", Formats.ago(now.minusSeconds(30), now));
    assertEquals("3 分钟前", Formats.ago(now.minusSeconds(200), now));
    assertEquals("2 小时前", Formats.ago(now.minusSeconds(7300), now));
    assertEquals("4 天前", Formats.ago(now.minusSeconds(4 * 86_400 + 5), now));
    assertEquals(
        "09:58:07", Formats.when(Instant.parse("2026-10-27T09:58:07Z"), now, ZoneOffset.UTC));
    assertEquals(
        "10-26 18:05", Formats.when(Instant.parse("2026-10-26T18:05:00Z"), now, ZoneOffset.UTC));
  }

  @Test
  void chineseAndLatinAreSpaced() {
    assertEquals("无法连接 Airflow（x）", Messages.spaced("无法连接Airflow（x）"));
    assertEquals("引擎 API 拒绝了", Messages.spaced("引擎 API拒绝了"));
    assertEquals("作业 7 个", Messages.spaced("作业7个"));
  }

  @Test
  void latestKeepsOnlyTheNewestValueUntilTheUiRuns() {
    List<Runnable> queued = new ArrayList<>();
    Executor ui = queued::add;
    List<String> applied = new ArrayList<>();
    Latest<String> latest = new Latest<>(ui, applied::add);
    latest.offer("a");
    latest.offer("b");
    latest.offer("c");
    assertEquals(1, queued.size(), "one hand-off for the three values");
    queued.removeFirst().run();
    assertEquals(List.of("c"), applied);
    latest.offer("d");
    queued.removeFirst().run();
    assertEquals(List.of("c", "d"), applied);
  }
}
