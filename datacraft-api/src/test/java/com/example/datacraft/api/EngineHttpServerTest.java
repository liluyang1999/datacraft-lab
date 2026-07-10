package com.example.datacraft.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobExecutionRequest;
import com.example.datacraft.engine.JobExecutionResult;
import com.example.datacraft.engine.JobRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class EngineHttpServerTest {

  @Test
  void exposesHealthJobsAndRunEndpoints() throws Exception {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());

    try (EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), registry)) {
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> health =
          client.send(
              HttpRequest.newBuilder(server.uri("/health")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> jobs =
          client.send(
              HttpRequest.newBuilder(server.uri("/jobs")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> run =
          client.send(
              HttpRequest.newBuilder(server.uri("/jobs/echo/runs?lifecycle=dev&message=hello"))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, health.statusCode());
      assertTrue(health.body().contains("\"status\":\"UP\""));
      assertEquals(200, jobs.statusCode());
      assertTrue(jobs.body().contains("\"name\":\"echo\""));
      assertEquals(200, run.statusCode());
      assertTrue(run.body().contains("\"status\":\"SUCCEEDED\""));
      assertTrue(run.body().contains("\"message\":\"hello\""));
    }
  }

  @Test
  void returnsNotFoundForUnknownRoutes() throws Exception {
    try (EngineHttpServer server =
        EngineHttpServer.start(EngineHttpServerConfig.localEphemeral(), new JobRegistry())) {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(server.uri("/missing").toString()))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());

      assertEquals(404, response.statusCode());
    }
  }

  private static final class EchoJob implements DataJob {
    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "returns message query parameter";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return JobExecutionResult.success(
          request.jobName(),
          request.parameters().getOrDefault("message", ""),
          request.startedAt(),
          request.startedAt());
    }
  }
}
