package com.example.datacraft.desktop.ui;

import static com.example.datacraft.desktop.ui.FxTesting.find;
import static com.example.datacraft.desktop.ui.FxTesting.onFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.desktop.ui.component.LogPager;
import java.util.List;
import java.util.stream.IntStream;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LogPagerTest {
  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void pagesKeepEveryLineInOrderAndExposeTheirBoundaries() {
    onFx(
        () -> {
          var source = FXCollections.observableArrayList(content(0, 401));
          try (LogPager pager = new LogPager(source)) {
            assertEquals(401, pager.lineCount());
            assertEquals(content(400, 401), pager.lines().getItems());
            assertTrue(button(pager, "下一页").isDisabled());
            button(pager, "首页").fire();
            assertEquals(content(0, 100), pager.lines().getItems());
            assertTrue(button(pager, "上一页").isDisabled());
            button(pager, "下一页").fire();
            assertEquals(content(100, 200), pager.lines().getItems());
            assertFalse(button(pager, "上一页").isDisabled());
            assertFalse(button(pager, "下一页").isDisabled());
            button(pager, "上一页").fire();
            assertEquals(content(0, 100), pager.lines().getItems());
            button(pager, "末页").fire();
            assertEquals(content(400, 401), pager.lines().getItems());
          }
        });
  }

  @Test
  void appendsFollowTheTailAndLeaveAnEarlierPageAndSelectionAlone() {
    onFx(
        () -> {
          var source = FXCollections.observableArrayList(content(0, 200));
          try (LogPager pager = new LogPager(source)) {
            source.addAll(content(200, 405));
            assertEquals(content(400, 405), pager.lines().getItems());
            button(pager, "首页").fire();
            pager.lines().getSelectionModel().select(17);
            source.addAll(content(405, 610));
            assertEquals(content(0, 100), pager.lines().getItems());
            assertEquals(17, pager.lines().getSelectionModel().getSelectedIndex());
            button(pager, "末页").fire();
            source.add("line 610");
            assertEquals(content(600, 611), pager.lines().getItems());
          }
        });
  }

  @Test
  void clearingOrShrinkingClampsThePageAndClosingDetachesTheSource() {
    onFx(
        () -> {
          var source = FXCollections.observableArrayList(content(0, 601));
          LogPager pager = new LogPager(source);
          button(pager, "上一页").fire();
          source.setAll(content(0, 5));
          assertEquals(content(0, 5), pager.lines().getItems());
          source.clear();
          assertEquals(0, pager.lineCount());
          assertTrue(pager.lines().getItems().isEmpty());
          assertTrue(find(pager, Button.class).stream().allMatch(Button::isDisabled));
          source.addAll(content(0, 201));
          assertEquals(content(200, 201), pager.lines().getItems());
          pager.close();
          pager.close();
          source.clear();
          assertEquals(201, pager.lineCount());
          assertEquals(content(200, 201), pager.lines().getItems());
        });
  }

  private static List<String> content(int start, int end) {
    return IntStream.range(start, end).mapToObj(line -> "line " + line).toList();
  }

  private static Button button(LogPager pager, String text) {
    return find(pager, Button.class).stream()
        .filter(button -> text.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }
}
