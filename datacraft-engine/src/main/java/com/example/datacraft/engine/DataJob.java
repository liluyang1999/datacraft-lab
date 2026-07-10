package com.example.datacraft.engine;

public interface DataJob {

  String name();

  String description();

  JobExecutionResult run(JobExecutionRequest request);
}
