package com.example.datacraft.jobs;

import com.example.datacraft.engine.DataJob;
import com.example.datacraft.engine.JobRegistry;
import java.util.List;

/**
 * Plain-JVM data jobs for small-scenario work: they run through the same {@link DataJob} contract
 * as the Spark jobs but need no Spark runtime. Their {@code input} is confined under {@code
 * DATACRAFT_DATA_ROOT} when that environment variable is set.
 */
public final class JvmJobs {

  private JvmJobs() {}

  public static List<DataJob> all() {
    return List.of(new CsvProfileJob(), new FileChecksumJob());
  }

  /** Registers every JVM data job into the registry and returns it for chaining. */
  public static JobRegistry register(JobRegistry registry) {
    if (registry == null) {
      throw new IllegalArgumentException("Job registry must not be null.");
    }
    all().forEach(registry::register);
    return registry;
  }
}
