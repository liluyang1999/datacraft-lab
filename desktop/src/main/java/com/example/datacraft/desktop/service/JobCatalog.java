package com.example.datacraft.desktop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parameters the app knows for the built-in jobs and DAGs, from {@code job-catalog.json}.
 * Neither backend publishes its parameters, so this catalogue only helps to fill a form: unknown
 * jobs and keys still run.
 */
public final class JobCatalog {

  /** One known parameter. An empty default means the backend has none or it is not fixed. */
  public record Param(
      String key, boolean required, String defaultValue, List<String> choices, String hint) {
    public Param {
      Objects.requireNonNull(key, "key");
      defaultValue = defaultValue == null ? "" : defaultValue;
      choices = List.copyOf(choices);
      hint = hint == null ? "" : hint;
    }
  }

  /** Where a job runs, which decides its glyph: in the JVM, on Spark, or inside the engine. */
  public enum Kind {
    JVM,
    SPARK,
    BUILT_IN,
    OTHER;

    static Kind of(String text) {
      return switch (text == null ? "" : text) {
        case "jvm" -> JVM;
        case "spark" -> SPARK;
        case "builtin" -> BUILT_IN;
        default -> OTHER;
      };
    }
  }

  /** A job or a DAG with its parameters, in the order a form shows them. */
  public record Entry(String name, String summary, Kind kind, List<Param> params) {
    public Entry {
      params = List.copyOf(params);
    }

    public Optional<Param> param(String key) {
      return params.stream().filter(param -> param.key().equals(key)).findFirst();
    }

    /** The required keys that {@code values} leaves blank, in catalogue order. */
    public List<String> missing(Map<String, String> values) {
      List<String> missing = new ArrayList<>();
      for (Param param : params) {
        if (param.required() && values.getOrDefault(param.key(), "").isBlank()) {
          missing.add(param.key());
        }
      }
      return missing;
    }
  }

  private final Map<String, Entry> jobs;
  private final Map<String, Entry> dags;

  JobCatalog(Map<String, Entry> jobs, Map<String, Entry> dags) {
    this.jobs = Map.copyOf(jobs);
    this.dags = Map.copyOf(dags);
  }

  /** The catalogue bundled with the app. */
  public static JobCatalog bundled(ObjectMapper json) {
    try (InputStream in =
        JobCatalog.class.getResourceAsStream("/com/example/datacraft/desktop/job-catalog.json")) {
      if (in == null) {
        throw new IllegalStateException("job-catalog.json is missing from the application");
      }
      return parse(json.readTree(in));
    } catch (IOException failure) {
      throw new UncheckedIOException("job-catalog.json cannot be read", failure);
    }
  }

  static JobCatalog parse(JsonNode root) {
    return new JobCatalog(entries(root.path("jobs")), entries(root.path("dags")));
  }

  public Optional<Entry> job(String name) {
    return Optional.ofNullable(jobs.get(name));
  }

  public Optional<Entry> dag(String dagId) {
    return Optional.ofNullable(dags.get(dagId));
  }

  private static Map<String, Entry> entries(JsonNode list) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    for (JsonNode entry : list) {
      List<Param> params = new ArrayList<>();
      for (JsonNode param : entry.path("params")) {
        List<String> choices = new ArrayList<>();
        param.path("choices").forEach(choice -> choices.add(choice.asText()));
        params.add(
            new Param(
                param.path("key").asText(),
                param.path("required").asBoolean(false),
                param.path("default").asText(""),
                choices,
                param.path("hint").asText("")));
      }
      String name = entry.path("name").asText();
      entries.put(
          name,
          new Entry(
              name,
              entry.path("summary").asText(""),
              Kind.of(entry.path("kind").asText("")),
              params));
    }
    return entries;
  }
}
