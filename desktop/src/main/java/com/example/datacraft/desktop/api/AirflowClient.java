package com.example.datacraft.desktop.api;

import com.example.datacraft.desktop.api.AirflowModels.Component;
import com.example.datacraft.desktop.api.AirflowModels.Dag;
import com.example.datacraft.desktop.api.AirflowModels.DagRun;
import com.example.datacraft.desktop.api.AirflowModels.Health;
import com.example.datacraft.desktop.api.AirflowModels.LogChunk;
import com.example.datacraft.desktop.api.AirflowModels.Task;
import com.example.datacraft.desktop.api.AirflowModels.TaskInstance;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Airflow 3.3's REST API v2 with the FAB auth manager. Signs in with {@code POST /auth/token},
 * keeps the JWT in memory only, renews it 60 s before it expires, and signs in again once when a
 * request gets 401. Endpoints and fields follow the OpenAPI definition shipped in {@code
 * apache/airflow:3.3.2}.
 */
public final class AirflowClient {

  static final Duration RENEW_BEFORE = Duration.ofSeconds(60);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
  // Thread-safe once configured; building a mapper per run would cost milliseconds each.
  private static final com.fasterxml.jackson.databind.ObjectMapper CONF_READER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private record Token(String value, Instant expiresAt) {}

  private final HttpTransport transport;
  private final URI base;
  private final String user;
  private final Optional<String> password;
  private final Clock clock;
  private volatile Token token;

  public AirflowClient(
      HttpTransport transport, URI base, String user, Optional<String> password, Clock clock) {
    this.transport = transport;
    this.base = base;
    this.user = user == null ? "" : user;
    this.password = password;
    this.clock = clock;
  }

  public URI base() {
    return base;
  }

  /** Signs in now and keeps the token; fails with UNAUTHORIZED for a wrong user or password. */
  public CompletableFuture<Void> login() {
    return signIn().thenAccept(ignored -> {});
  }

  private CompletableFuture<String> signIn() {
    if (user.isBlank() || password.isEmpty()) {
      return CompletableFuture.failedFuture(
          new ApiException(ApiError.UNAUTHORIZED, 0, "没有设置 Airflow 用户名或口令"));
    }
    ObjectNode body =
        transport.json().createObjectNode().put("username", user).put("password", password.get());
    HttpRequest request =
        transport
            .request(base, "/auth/token", HttpTransport.READ_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
    return transport
        .send(request)
        .thenApply(
            response -> {
              if (response.status() != 200 && response.status() != 201) {
                throw failure(response);
              }
              String value = transport.parse(response).path("access_token").asText("");
              if (value.isEmpty()) {
                throw new ApiException(
                    ApiError.PROTOCOL, response.status(), "no access_token in the answer");
              }
              token =
                  new Token(
                      value, expiry(value).orElse(clock.instant().plus(Duration.ofMinutes(10))));
              return value;
            });
  }

  /** Health needs no sign-in. */
  public CompletableFuture<Health> health() {
    HttpRequest request =
        transport.request(base, "/api/v2/monitor/health", HttpTransport.READ_TIMEOUT).GET().build();
    return transport
        .send(request)
        .thenApply(
            response -> {
              if (response.status() != 200) {
                throw failure(response);
              }
              JsonNode body = transport.parse(response);
              JsonNode processor = body.path("dag_processor");
              return new Health(
                  component(body.path("metadatabase"), null),
                  component(body.path("scheduler"), "latest_scheduler_heartbeat"),
                  component(body.path("triggerer"), "latest_triggerer_heartbeat"),
                  processor.isObject()
                      ? Optional.of(component(processor, "latest_dag_processor_heartbeat"))
                      : Optional.empty(),
                  response.latency());
            });
  }

  public CompletableFuture<String> version() {
    return authorized("GET", "/api/v2/version", null, body -> body.path("version").asText(""));
  }

  public CompletableFuture<List<Dag>> dags() {
    return authorized(
        "GET",
        "/api/v2/dags?limit=100&order_by=dag_id&exclude_stale=true",
        null,
        body -> {
          List<Dag> dags = new ArrayList<>();
          for (JsonNode dag : array(body, "dags")) {
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : dag.path("tags")) {
              tags.add(tag.isTextual() ? tag.asText() : tag.path("name").asText(""));
            }
            dags.add(
                new Dag(
                    dag.path("dag_id").asText(),
                    dag.path("dag_display_name").asText(dag.path("dag_id").asText()),
                    dag.path("is_paused").asBoolean(false),
                    text(dag, "description"),
                    text(dag, "timetable_summary"),
                    tags,
                    dag.path("has_import_errors").asBoolean(false),
                    instant(dag, "next_dagrun_run_after")));
          }
          return List.copyOf(dags);
        });
  }

  public CompletableFuture<Void> setPaused(String dagId, boolean paused) {
    String body = transport.json().createObjectNode().put("is_paused", paused).toString();
    return authorized(
        "PATCH", "/api/v2/dags/" + HttpTransport.encode(dagId), body, ignored -> null);
  }

  /** The newest runs of a DAG, newest first. */
  public CompletableFuture<List<DagRun>> runs(String dagId, int limit) {
    return authorized(
        "GET",
        "/api/v2/dags/"
            + HttpTransport.encode(dagId)
            + "/dagRuns?limit="
            + limit
            + "&order_by=-run_after",
        null,
        body -> {
          List<DagRun> runs = new ArrayList<>();
          for (JsonNode run : array(body, "dag_runs")) {
            runs.add(run(run));
          }
          return List.copyOf(runs);
        });
  }

  /** Triggers a run now ({@code logical_date: null}) with the given configuration. */
  public CompletableFuture<DagRun> trigger(String dagId, Map<String, Object> conf, String note) {
    ObjectNode body = transport.json().createObjectNode();
    body.putNull("logical_date");
    body.set("conf", transport.json().valueToTree(conf));
    if (note != null && !note.isBlank()) {
      body.put("note", note.strip());
    }
    return authorized(
        "POST",
        "/api/v2/dags/" + HttpTransport.encode(dagId) + "/dagRuns",
        body.toString(),
        AirflowClient::run);
  }

  /** The tasks of a DAG with their downstream tasks: the shape of the pipeline graph. */
  public CompletableFuture<List<Task>> tasks(String dagId) {
    return authorized(
        "GET",
        "/api/v2/dags/" + HttpTransport.encode(dagId) + "/tasks",
        null,
        body -> {
          List<Task> tasks = new ArrayList<>();
          for (JsonNode task : array(body, "tasks")) {
            List<String> downstream = new ArrayList<>();
            for (JsonNode next : task.path("downstream_task_ids")) {
              downstream.add(next.asText());
            }
            String taskId = task.path("task_id").asText();
            tasks.add(
                new Task(
                    taskId,
                    task.path("task_display_name").asText(taskId),
                    downstream,
                    text(task, "operator_name")));
          }
          return List.copyOf(tasks);
        });
  }

  public CompletableFuture<List<TaskInstance>> taskInstances(String dagId, String runId) {
    return authorized(
        "GET",
        "/api/v2/dags/"
            + HttpTransport.encode(dagId)
            + "/dagRuns/"
            + HttpTransport.encode(runId)
            + "/taskInstances?limit=100&order_by=start_date",
        null,
        body -> {
          List<TaskInstance> tasks = new ArrayList<>();
          for (JsonNode task : array(body, "task_instances")) {
            tasks.add(
                new TaskInstance(
                    task.path("task_id").asText(),
                    task.path("task_display_name").asText(task.path("task_id").asText()),
                    task.path("map_index").asInt(-1),
                    task.path("state").isTextual()
                        ? Optional.of(task.path("state").asText())
                        : Optional.empty(),
                    task.path("try_number").asInt(0),
                    task.path("max_tries").asInt(0),
                    instant(task, "start_date"),
                    instant(task, "end_date"),
                    duration(task),
                    text(task, "operator_name").isEmpty()
                        ? text(task, "operator")
                        : text(task, "operator_name")));
          }
          return List.copyOf(tasks);
        });
  }

  /** One part of a task's log; pass the returned {@code next} to read on. */
  public CompletableFuture<LogChunk> log(
      String dagId, String runId, String taskId, int mapIndex, int tryNumber, String next) {
    String path =
        "/api/v2/dags/"
            + HttpTransport.encode(dagId)
            + "/dagRuns/"
            + HttpTransport.encode(runId)
            + "/taskInstances/"
            + HttpTransport.encode(taskId)
            + "/logs/"
            + tryNumber
            + "?full_content=false"
            + (mapIndex >= 0 ? "&map_index=" + mapIndex : "")
            + (next == null || next.isEmpty() ? "" : "&token=" + HttpTransport.encode(next));
    return authorized(
        "GET",
        path,
        null,
        body -> {
          List<String> lines = new ArrayList<>();
          for (JsonNode entry : body.path("content")) {
            if (entry.isTextual()) {
              lines.add(entry.asText());
            } else if (entry.isArray()) {
              for (JsonNode part : entry) {
                lines.add(part.isTextual() ? part.asText() : logLine(part));
              }
            } else {
              lines.add(logLine(entry));
            }
          }
          String continuation = body.path("continuation_token").asText("");
          return new LogChunk(
              lines, continuation.isEmpty() ? Optional.empty() : Optional.of(continuation));
        });
  }

  private <T> CompletableFuture<T> authorized(
      String method, String path, String json, Function<JsonNode, T> read) {
    return withToken()
        .thenCompose(value -> send(method, path, json, value))
        .thenCompose(
            response -> {
              if (response.status() != 401) {
                return CompletableFuture.completedFuture(response);
              }
              // The token expired or the server restarted: sign in once more, then give up.
              token = null;
              return withToken().thenCompose(value -> send(method, path, json, value));
            })
        .thenApply(
            response -> {
              if (response.status() / 100 != 2) {
                throw failure(response);
              }
              return read.apply(
                  response.body().length == 0 && "PATCH".equals(method)
                      ? transport.json().nullNode()
                      : transport.parse(response));
            });
  }

  private CompletableFuture<String> withToken() {
    Token current = token;
    if (current != null && clock.instant().isBefore(current.expiresAt().minus(RENEW_BEFORE))) {
      return CompletableFuture.completedFuture(current.value());
    }
    return signIn();
  }

  private CompletableFuture<HttpTransport.Response> send(
      String method, String path, String json, String bearer) {
    HttpRequest.Builder builder =
        transport
            .request(base, path, HttpTransport.READ_TIMEOUT)
            .header("Authorization", "Bearer " + bearer);
    if (json == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }
    return transport.send(builder.build());
  }

  /** The {@code exp} claim of a JWT, if it has one; the signature is the server's business. */
  static Optional<Instant> expiry(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2) {
      return Optional.empty();
    }
    try {
      byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
      JsonNode claims = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
      return claims.path("exp").canConvertToLong()
          ? Optional.of(Instant.ofEpochSecond(claims.path("exp").asLong()))
          : Optional.empty();
    } catch (IllegalArgumentException | java.io.IOException malformed) {
      return Optional.empty();
    }
  }

  private ApiException failure(HttpTransport.Response response) {
    String detail = "";
    try {
      JsonNode body = transport.json().readTree(response.body());
      JsonNode value = body == null ? null : body.get("detail");
      if (value != null) {
        detail = value.isTextual() ? value.asText() : detailOf(value);
      }
    } catch (java.io.IOException notJson) {
      // A proxy page or an empty body: keep the status only.
    }
    ApiError error =
        switch (response.status()) {
          case 401 -> ApiError.UNAUTHORIZED;
          case 403 -> ApiError.FORBIDDEN;
          case 404 -> ApiError.NOT_FOUND;
          case 400, 409, 422 -> ApiError.REJECTED;
          default -> response.status() >= 500 ? ApiError.SERVER : ApiError.PROTOCOL;
        };
    return new ApiException(error, response.status(), detail);
  }

  /**
   * A structured detail as one line: a 422's validation list as "conf.input: field required; ...",
   * and an object (a 409's {reason, statement, orig_error}) as its reason only, so that no SQL or
   * server internals reach the interface.
   */
  private static String detailOf(JsonNode detail) {
    if (detail.isObject()) {
      for (String field : List.of("reason", "message", "msg")) {
        if (detail.path(field).isTextual()) {
          return detail.path(field).asText();
        }
      }
      return "";
    }
    if (!detail.isArray()) {
      return detail.asText("");
    }
    List<String> parts = new ArrayList<>();
    for (JsonNode item : detail) {
      List<String> location = new ArrayList<>();
      for (JsonNode segment : item.path("loc")) {
        if (!"body".equals(segment.asText())) {
          location.add(segment.asText());
        }
      }
      parts.add(String.join(".", location) + ": " + item.path("msg").asText(""));
    }
    return String.join("; ", parts);
  }

  private static DagRun run(JsonNode run) {
    Map<String, Object> conf = Map.of();
    if (run.path("conf").isObject()) {
      try {
        conf = CONF_READER.treeToValue(run.path("conf"), MAP);
      } catch (JsonProcessingException | IllegalArgumentException malformed) {
        conf = Map.of();
      }
    }
    return new DagRun(
        run.path("dag_run_id").asText(),
        run.path("dag_id").asText(),
        run.path("state").asText(""),
        run.path("run_type").asText(""),
        instant(run, "queued_at"),
        instant(run, "start_date"),
        instant(run, "end_date"),
        duration(run),
        instant(run, "run_after").orElse(Instant.EPOCH),
        conf,
        text(run, "note"),
        text(run, "triggered_by"));
  }

  private static Component component(JsonNode node, String heartbeat) {
    return new Component(
        node.path("status").asText("unknown"),
        heartbeat == null ? Optional.empty() : instant(node, heartbeat));
  }

  private static String logLine(JsonNode entry) {
    String time = entry.path("timestamp").asText("");
    String level = entry.path("level").asText("");
    String event = entry.path("event").asText(entry.toString());
    StringBuilder line = new StringBuilder();
    if (!time.isEmpty()) {
      line.append(time).append("  ");
    }
    if (!level.isEmpty()) {
      line.append(level.toUpperCase(java.util.Locale.ROOT)).append("  ");
    }
    return line.append(event).toString();
  }

  private static Iterable<JsonNode> array(JsonNode body, String field) {
    JsonNode node = body.path(field);
    if (!node.isArray()) {
      throw new ApiException(ApiError.PROTOCOL, 200, "the answer has no \"" + field + "\" array");
    }
    return node;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText() : "";
  }

  static Optional<Instant> instant(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(value.asText()).toInstant());
    } catch (DateTimeParseException malformed) {
      return Optional.empty();
    }
  }

  private static Optional<Duration> duration(JsonNode node) {
    JsonNode value = node.path("duration");
    return value.isNumber()
        ? Optional.of(Duration.ofMillis(Math.round(value.asDouble() * 1000)))
        : Optional.empty();
  }
}
