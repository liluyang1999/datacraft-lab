package com.example.datacraft.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.glass.ui.Accessible;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native readers can retain a text range after its control has left the scene. */
class AccessibilityLifecycleTest {

  @BeforeAll
  static void toolkit() {
    FxTesting.start();
  }

  @Test
  void disposedCallbacksAreSafeWithoutHidingFailuresFromLiveHandlers() {
    FxTesting.onFx(
        () -> {
          int[] actions = {0};
          Accessible accessible =
              new Accessible() {
                @Override
                protected long getNativeAccessible() {
                  return 0;
                }

                @Override
                public void sendNotification(AccessibleAttribute attribute) {}
              };
          accessible.setEventHandler(
              new Accessible.EventHandler() {
                @Override
                public Object getAttribute(AccessibleAttribute attribute, Object... parameters) {
                  if (attribute == AccessibleAttribute.TEXT) {
                    return "live text";
                  }
                  throw new IllegalStateException("live handler failure");
                }

                @Override
                public void executeAction(AccessibleAction action, Object... parameters) {
                  actions[0]++;
                }
              });
          assertEquals("live text", accessible.getAttribute(AccessibleAttribute.TEXT));
          assertThrows(
              IllegalStateException.class,
              () -> accessible.getAttribute(AccessibleAttribute.VALUE));
          accessible.executeAction(AccessibleAction.SET_TEXT, "live");
          accessible.dispose();
          assertNull(accessible.getAttribute(AccessibleAttribute.TEXT));
          accessible.executeAction(AccessibleAction.SET_TEXT, "stale");
          assertEquals(1, actions[0]);
        });
    FxTesting.assertHealthy();
  }

  @Test
  @Tag("windows-only")
  void detachedControlsNoLongerAnswerQueriesOrExecuteActions() {
    var fixture =
        FxTesting.onFx(
            () -> {
              TextArea text = new TextArea("保留的文本 range");
              Stage stage = new Stage();
              StackPane host = new StackPane(text);
              stage.setScene(new Scene(host, 400, 200));
              stage.show();
              Object accessible = invoke(Node.class, text, "getAccessible");
              return new Fixture(stage, host, text, accessible);
            });
    try {
      FxTesting.onFx(
          () -> {
            Class<?> type = Class.forName("com.sun.glass.ui.Accessible");
            Method query = method(type, "getAttribute", AccessibleAttribute.class, Object[].class);
            Method action = method(type, "executeAction", AccessibleAction.class, Object[].class);
            assertEquals(
                fixture.text().getText(),
                query.invoke(fixture.accessible(), AccessibleAttribute.TEXT, new Object[0]));
            action.invoke(
                fixture.accessible(), AccessibleAction.SET_TEXT, new Object[] {"live edit"});
            assertEquals("live edit", fixture.text().getText());
            fixture.host().getChildren().clear();
            return null;
          });
      FxTesting.settle(); // Scene disposes the detached peer on the next pulse.
      FxTesting.onFx(
          () -> {
            Class<?> type = Class.forName("com.sun.glass.ui.Accessible");
            assertNull(invoke(type, fixture.accessible(), "getEventHandler"));
            Method query = method(type, "getAttribute", AccessibleAttribute.class, Object[].class);
            assertNull(query.invoke(fixture.accessible(), AccessibleAttribute.TEXT, new Object[0]));
            method(type, "executeAction", AccessibleAction.class, Object[].class)
                .invoke(
                    fixture.accessible(), AccessibleAction.SET_TEXT, new Object[] {"stale edit"});
            assertEquals("live edit", fixture.text().getText());
            fixture.host().getChildren().add(fixture.text());
            Object replacement = invoke(Node.class, fixture.text(), "getAccessible");
            assertEquals(
                "live edit", query.invoke(replacement, AccessibleAttribute.TEXT, new Object[0]));
            return null;
          });
    } finally {
      FxTesting.onFx(fixture.stage()::close);
    }
    FxTesting.assertHealthy();
  }

  private record Fixture(Stage stage, StackPane host, TextArea text, Object accessible) {}

  @Test
  @Tag("windows-only")
  void unsupportedWindowsPropertiesReturnEmptyInsteadOfProviderErrors() {
    FxTesting.onFx(
        () -> {
          TextArea text = new TextArea("可访问文本");
          Stage stage = new Stage();
          stage.setScene(new Scene(new StackPane(text), 400, 200));
          stage.show();
          Object accessible = invoke(Node.class, text, "getAccessible");
          Method property = method(accessible.getClass(), "GetPropertyValue", int.class);
          try {
            // The native bridge maps null to E_FAIL, but UIA requires S_OK + VT_EMPTY for
            // unsupported properties. Culture, ClassName and future IDs must all be harmless.
            for (int id : new int[] {30015, 30012, Integer.MAX_VALUE}) {
              Object variant = property.invoke(accessible, id);
              assertNotNull(variant, "unsupported property " + id + " must not signal E_FAIL");
              var kind = variant.getClass().getDeclaredField("vt");
              kind.setAccessible(true);
              assertEquals((short) 0, kind.getShort(variant));
            }
            Object controlType = property.invoke(accessible, 30003);
            var value = controlType.getClass().getDeclaredField("lVal");
            value.setAccessible(true);
            assertEquals(50004, value.getInt(controlType)); // UIA_EditControlTypeId
            Accessible peer = (Accessible) accessible;
            Accessible.EventHandler originalHandler = peer.getEventHandler();
            peer.setEventHandler(
                new Accessible.EventHandler() {
                  @Override
                  public Object getAttribute(AccessibleAttribute attribute, Object... parameters) {
                    throw new IllegalStateException("live property failure");
                  }
                });
            try {
              var failure =
                  assertThrows(
                      java.lang.reflect.InvocationTargetException.class,
                      () -> property.invoke(accessible, 30003));
              assertEquals("live property failure", failure.getCause().getMessage());
            } finally {
              peer.setEventHandler(originalHandler);
            }
            invoke(Node.class, text, "releaseAccessible");
            // A disposed provider remains a failure, rather than masquerading as a live peer.
            assertNull(property.invoke(accessible, 30003));
          } finally {
            stage.close();
          }
          return null;
        });
    FxTesting.assertHealthy();
  }

  @Test
  @Tag("windows-only")
  void windowsTextRangeCanOutliveItsAccessible() {
    FxTesting.onFx(
        () -> {
          TextArea text = new TextArea("原生文本 retained range");
          Stage stage = new Stage();
          stage.setScene(new Scene(new StackPane(text), 400, 200));
          stage.show();
          Object accessible = invoke(Node.class, text, "getAccessible");
          Class<?> rangeType = Class.forName("com.sun.glass.ui.win.WinTextRangeProvider");
          Constructor<?> constructor = rangeType.getDeclaredConstructor(accessible.getClass());
          constructor.setAccessible(true);
          Object range = constructor.newInstance(accessible);
          method(rangeType, "setRange", int.class, int.class).invoke(range, 0, text.getLength());
          Method getText = method(rangeType, "GetText", int.class);
          try {
            assertEquals(text.getText(), getText.invoke(range, -1));
            assertEquals("原生", getText.invoke(range, 2));
            invoke(Node.class, text, "releaseAccessible");
            assertNull(getText.invoke(range, -1));
            assertNull(getText.invoke(range, 2));
          } finally {
            invoke(rangeType, range, "dispose");
            stage.close();
          }
          return null;
        });
    FxTesting.assertHealthy();
  }

  private static Object invoke(Class<?> type, Object instance, String name) throws Exception {
    return method(type, name).invoke(instance);
  }

  private static Method method(Class<?> type, String name, Class<?>... parameters)
      throws Exception {
    Method method = type.getDeclaredMethod(name, parameters);
    method.setAccessible(true);
    return method;
  }
}
