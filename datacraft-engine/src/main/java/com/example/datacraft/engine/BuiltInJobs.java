package com.example.datacraft.engine;

public final class BuiltInJobs {

  private BuiltInJobs() {}

  public static JobRegistry registry() {
    JobRegistry registry = new JobRegistry();
    registry.register(new EchoJob());
    registry.register(new NoopJob());
    return registry;
  }

  private static final class EchoJob implements DataJob {

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Returns the message parameter.";
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

  private static final class NoopJob implements DataJob {

    @Override
    public String name() {
      return "noop";
    }

    @Override
    public String description() {
      return "Confirms the engine is reachable without processing data.";
    }

    @Override
    public JobExecutionResult run(JobExecutionRequest request) {
      return JobExecutionResult.success(
          request.jobName(), "noop", request.startedAt(), request.startedAt());
    }
  }
}
