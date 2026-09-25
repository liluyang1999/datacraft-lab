package com.example.datacraft.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class JobRegistry implements JobCatalog {

  private final TreeMap<String, DataJob> jobs = new TreeMap<>();

  public void register(DataJob job) {
    if (job == null) {
      throw new IllegalArgumentException("Job must not be null.");
    }
    if (job.name() == null || job.name().isBlank()) {
      throw new IllegalArgumentException("Job name must not be blank.");
    }
    // Every job name is a single URL path segment of POST /jobs/{name}/runs.
    if (job.name().indexOf('/') >= 0) {
      throw new IllegalArgumentException("Job name must not contain '/': " + job.name());
    }
    if (jobs.containsKey(job.name())) {
      throw new IllegalArgumentException("Duplicate job name: " + job.name());
    }
    jobs.put(job.name(), job);
  }

  @Override
  public Optional<DataJob> find(String jobName) {
    return Optional.ofNullable(jobs.get(jobName));
  }

  @Override
  public List<String> jobNames() {
    return List.copyOf(jobs.keySet());
  }

  @Override
  public Map<String, DataJob> jobs() {
    return Collections.unmodifiableMap(new TreeMap<>(jobs));
  }
}
