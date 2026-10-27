package com.example.datacraft.desktop.store;

import com.example.datacraft.desktop.util.Maps;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A cache of the job runs this app started, per profile: what was run, when, and how it ended. It
 * keeps at most {@link #KEEP_PER_PROFILE} runs per profile and none older than {@link #KEEP_FOR};
 * the job results themselves stay with the engine.
 */
public final class RecentRunRepository {

  public static final int KEEP_PER_PROFILE = 200;
  public static final Duration KEEP_FOR = Duration.ofDays(14);

  /** One recorded run. */
  public record RecentRun(
      long profileId,
      String jobName,
      Map<String, String> params,
      String status,
      String message,
      Map<String, String> metrics,
      Instant startedAt,
      Duration duration) {

    public RecentRun {
      params = Maps.frozen(params);
      metrics = Maps.frozen(metrics);
    }
  }

  private final LocalStore store;

  public RecentRunRepository(LocalStore store) {
    this.store = store;
  }

  /** Records a run and trims the cache of its profile. */
  public CompletableFuture<Void> add(RecentRun run, Instant now) {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO recent_runs (profile_id, job_name, params, status, message, metrics,"
                      + " started_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, run.profileId());
            statement.setString(2, run.jobName());
            statement.setString(3, PresetRepository.write(new LinkedHashMap<>(run.params())));
            statement.setString(4, run.status());
            statement.setString(5, run.message());
            statement.setString(6, PresetRepository.write(new LinkedHashMap<>(run.metrics())));
            statement.setLong(7, run.startedAt().toEpochMilli());
            statement.setLong(8, run.duration().toMillis());
            statement.executeUpdate();
          }
          try (PreparedStatement old =
                  connection.prepareStatement("DELETE FROM recent_runs WHERE started_at < ?");
              PreparedStatement surplus =
                  connection.prepareStatement(
                      "DELETE FROM recent_runs WHERE profile_id = ? AND id NOT IN (SELECT id FROM"
                          + " recent_runs WHERE profile_id = ? ORDER BY started_at DESC, id DESC LIMIT ?)")) {
            old.setLong(1, now.minus(KEEP_FOR).toEpochMilli());
            old.executeUpdate();
            surplus.setLong(1, run.profileId());
            surplus.setLong(2, run.profileId());
            surplus.setInt(3, KEEP_PER_PROFILE);
            surplus.executeUpdate();
          }
          return null;
        });
  }

  /** The newest runs of a profile, optionally of one job only, newest first. */
  public CompletableFuture<List<RecentRun>> list(long profileId, String jobName, int limit) {
    return store.submit(
        connection -> {
          String sql =
              "SELECT job_name, params, status, message, metrics, started_at, duration_ms FROM recent_runs"
                  + " WHERE profile_id = ?"
                  + (jobName == null ? "" : " AND job_name = ?")
                  + " ORDER BY started_at DESC, id DESC LIMIT ?";
          List<RecentRun> runs = new ArrayList<>();
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, profileId);
            if (jobName != null) {
              statement.setString(index++, jobName);
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
              while (result.next()) {
                runs.add(
                    new RecentRun(
                        profileId,
                        result.getString(1),
                        PresetRepository.parse(result.getString(2)),
                        result.getString(3),
                        result.getString(4),
                        PresetRepository.parse(result.getString(5)),
                        Instant.ofEpochMilli(result.getLong(6)),
                        Duration.ofMillis(result.getLong(7))));
              }
            }
          }
          return runs;
        });
  }

  public CompletableFuture<Void> clear() {
    return store.transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM recent_runs")) {
            statement.executeUpdate();
          }
          return null;
        });
  }
}
