package com.example.datacraft.desktop.api;

import com.example.datacraft.desktop.api.EngineModels.ActiveRun;
import com.example.datacraft.desktop.api.EngineModels.Health;
import com.example.datacraft.desktop.api.EngineModels.JobInfo;
import com.example.datacraft.desktop.api.EngineModels.JobResult;
import com.example.datacraft.desktop.api.EngineModels.JobStatus;
import com.example.datacraft.desktop.api.EngineModels.Status;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The engine API ({@code datacraft-api}): health, status, the job catalogue and synchronous runs,
 * with the status contract of {@code design/data-contracts.md}. The token is sent on {@code
 * /status} and {@code /jobs} requests; {@code /health} needs none.
 */
public final class EngineClient {

  private final HttpTransport transport;
  private final URI base;
  private final Optional<String> token;

  public EngineClient(HttpTransport transport, URI base, Optional<String> token) {
    this.transport = transport;
    this.base = base;
    this.token = token.filter(value -> !value.isBlank()).map(String::strip);
  }

  public URI base() {
    return base;
  }

  public CompletableFuture<Health> health() {
    HttpRequest request =
        transport.request(base, "/health", HttpTransport.READ_TIMEOUT).GET().build();
    return transport
        .send(request)
        .thenApply(
            response -> {
              if (response.status() != 200) {
                throw failure(response);
              }
              JsonNode body = transport.parse(response);
              return new Health(
                  "UP".equals(body.path("status").asText()),
                  body.path("service").asText(""),
                  response.latency());
            });
  }

  /**
   * The engine's version, uptime, load and run counters ({@code GET /status}, which needs the
   * token). An engine that predates this route answers 404 ({@link ApiError#NOT_FOUND}).
   */
  public CompletableFuture<Status> status() {
    HttpRequest request =
        authorized(transport.request(base, "/status", HttpTransport.READ_TIMEOUT)).GET().build();
    return transport
        .send(request)
        .thenApply(
            response -> {
              if (response.status() != 200) {
                throw failure(response);
              }
              JsonNode body = transport.parse(response);
              JsonNode runs = body.path("runs");
              Instant started =
                  AirflowClient.instant(body, "startedAt")
                      .orElseThrow(
                          () ->
                              new ApiException(
                                  ApiError.PROTOCOL, 200, "the status has no \"startedAt\""));
              List<ActiveRun> active = new ArrayList<>();
              for (JsonNode run : body.path("activeRuns")) {
                String job = run.path("job").asText("");
                if (!job.isEmpty()) {
                  active.add(
                      new ActiveRun(
                          job,
                          AirflowClient.instant(run, "startedAt").orElse(started),
                          Duration.ofMillis(Math.max(0, run.path("elapsedMillis").asLong(0)))));
                }
              }
              return new Status(
                  body.path("version").asText(""),
                  started,
                  Duration.ofMillis(Math.max(0, body.path("uptimeMillis").asLong(0))),
                  body.path("jobs").asInt(0),
                  body.path("maxConcurrentRuns").asInt(0),
                  runs.path("started").asLong(0),
                  runs.path("succeeded").asLong(0),
                  runs.path("failed").asLong(0),
                  runs.path("rejected").asLong(0),
                  active);
            });
  }

  public CompletableFuture<List<JobInfo>> jobs() {
    HttpRequest request =
        authorized(transport.request(base, "/jobs", HttpTransport.READ_TIMEOUT)).GET().build();
    return transport
        .send(request)
        .thenApply(
            response -> {
              if (response.status() != 200) {
                throw failure(response);
              }
              JsonNode jobs = transport.parse(response).path("jobs");
              if (!jobs.isArray()) {
                throw new ApiException(
                    ApiError.PROTOCOL, 200, "the job list has no \"jobs\" array");
              }
              List<JobInfo> list = new ArrayList<>();
              for (JsonNode job : jobs) {
                String name = job.path("name").asText("");
                if (!name.isEmpty()) {
                  list.add(new JobInfo(name, job.path("description").asText("")));
                }
              }
              return List.copyOf(list);
            });
  }

  /**
   * Runs a job and waits for its result. A job that fails is a FAILED result, not an exception;
   * exceptions are for requests that did not run the job (401, 404, 503, a timeout...).
   */
  public CompletableFuture<JobResult> run(
      String jobName, Map<String, String> params, Duration timeout) {
    String path = "/jobs/" + HttpTransport.encode(jobName) + "/runs";
    if (!params.isEmpty()) {
      path += "?" + HttpTransport.query(params);
    }
    HttpRequest request =
        authorized(transport.request(base, path, timeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    CompletableFuture<HttpTransport.Response> exchange = transport.send(request);
    return HttpTransport.linked(
        exchange,
        exchange.thenApply(
            response -> {
              if (response.status() == 200 || response.status() == 500) {
                JsonNode body = transport.parse(response);
                if (body.has("status")) {
                  return result(body);
                }
              }
              throw failure(response);
            }));
  }

  private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
    token.ifPresent(value -> builder.header("Authorization", "Bearer " + value));
    return builder;
  }

  private static JobResult result(JsonNode body) {
    Map<String, String> metrics = new LinkedHashMap<>();
    body.path("metrics")
        .properties()
        .forEach(entry -> metrics.put(entry.getKey(), entry.getValue().asText()));
    return new JobResult(
        body.path("jobName").asText(""),
        JobStatus.of(body.path("status").asText("")),
        body.path("message").asText(""),
        metrics);
  }

  /** The documented error of an answer that is not a result. */
  private ApiException failure(HttpTransport.Response response) {
    String code = "";
    try {
      code = transport.json().readTree(response.body()).path("error").asText("");
    } catch (java.io.IOException notJson) {
      // An HTML 400 of the JDK server or a proxy page: keep the status only.
    }
    ApiError error =
        switch (response.status()) {
          case 401 -> ApiError.UNAUTHORIZED;
          case 403 -> ApiError.FORBIDDEN;
          case 404 -> ApiError.NOT_FOUND;
          case 503 -> ApiError.BUSY;
          case 400, 405 -> ApiError.REJECTED;
          default -> response.status() >= 500 ? ApiError.SERVER : ApiError.PROTOCOL;
        };
    return new ApiException(error, response.status(), code);
  }
}
