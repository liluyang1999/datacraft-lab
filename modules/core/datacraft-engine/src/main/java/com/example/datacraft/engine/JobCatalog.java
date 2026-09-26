package com.example.datacraft.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface JobCatalog {

  Optional<DataJob> find(String jobName);

  List<String> jobNames();

  Map<String, DataJob> jobs();

  default DataJob require(String jobName) {
    return find(jobName)
        .orElseThrow(() -> new IllegalArgumentException("Unknown job name: " + jobName));
  }
}
