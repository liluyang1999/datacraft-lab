package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.service.JobCatalog;
import com.example.datacraft.desktop.util.Maps;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared job and pipeline form rules: reject ambiguous rows before producing a request map. */
public final class ParamForm {
  private ParamForm() {}

  public record Values(Map<String, String> values, String error) {
    public Values {
      values = Maps.frozen(values);
    }

    public boolean valid() {
      return error.isEmpty();
    }
  }

  /** Blank values use backend defaults; unnamed values and duplicate keys are never discarded. */
  public static Values collect(List<ParamRow> rows) {
    Map<String, String> values = new LinkedHashMap<>();
    Set<String> keys = new HashSet<>();
    for (ParamRow row : rows) {
      String key = row.key().get() == null ? "" : row.key().get().strip();
      String value = row.value().get() == null ? "" : row.value().get();
      if (key.isEmpty() && !value.isBlank()) {
        return new Values(Map.of(), "请填写参数名称");
      }
      if (!key.isEmpty() && !keys.add(key)) {
        return new Values(Map.of(), "参数名称重复：" + key);
      }
      if (!key.isEmpty() && !value.isBlank()) {
        values.put(key, value);
      }
    }
    return new Values(values, "");
  }

  /** Known parameters keep their metadata; unknown keys remain editable and retain their values. */
  public static List<ParamRow> rows(Optional<JobCatalog.Entry> entry, Map<String, String> values) {
    List<ParamRow> rows = new ArrayList<>();
    entry.ifPresent(
        known ->
            known
                .params()
                .forEach(
                    param ->
                        rows.add(
                            new ParamRow(
                                Optional.of(param),
                                param.key(),
                                values.getOrDefault(param.key(), "")))));
    values.forEach(
        (key, value) -> {
          if (entry.flatMap(known -> known.param(key)).isEmpty()) {
            rows.add(new ParamRow(Optional.empty(), key, value));
          }
        });
    return rows;
  }
}
