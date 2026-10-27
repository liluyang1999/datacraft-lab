package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.api.RunTone;
import com.example.datacraft.desktop.service.BackendStatus;
import java.util.Locale;

/** The words for states, run types and schedules, the same everywhere in the interface. */
public final class Labels {

  private Labels() {}

  public static String tone(RunTone tone) {
    return switch (tone) {
      case SUCCESS -> "成功";
      case RUNNING -> "运行中";
      case WAITING -> "等待";
      case FAILED -> "失败";
      case NONE -> "无";
    };
  }

  /** An Airflow run or task state, or an engine result status. */
  public static String state(String state) {
    if (state == null || state.isBlank()) {
      return "未开始";
    }
    return switch (state.strip().toLowerCase(Locale.ROOT)) {
      case "success", "succeeded" -> "成功";
      case "failed" -> "失败";
      case "running" -> "运行中";
      case "queued" -> "排队中";
      case "scheduled" -> "已调度";
      case "up_for_retry" -> "等待重试";
      case "up_for_reschedule" -> "等待重新调度";
      case "upstream_failed" -> "上游失败";
      case "skipped" -> "跳过";
      case "deferred" -> "已延后";
      case "removed" -> "已移除";
      case "restarting" -> "重启中";
      case "awaiting_input" -> "等待输入";
      default -> state;
    };
  }

  public static String runType(String runType) {
    return switch (runType == null ? "" : runType) {
      case "manual" -> "手动";
      case "scheduled" -> "定时";
      case "backfill" -> "回填";
      case "asset_triggered" -> "资产触发";
      default -> runType == null ? "" : runType;
    };
  }

  /**
   * Airflow's timetable summary; {@code None} or nothing means the DAG only runs when triggered.
   */
  public static String schedule(String summary) {
    if (summary == null || summary.isBlank() || summary.equals("None")) {
      return "手动";
    }
    return switch (summary) {
      case "@daily" -> "每天";
      case "@hourly" -> "每小时";
      case "@weekly" -> "每周";
      case "@monthly" -> "每月";
      default -> summary;
    };
  }

  /** The short word next to a status dot in the top bar and the overview. */
  public static String status(BackendStatus status) {
    return switch (status.state()) {
      case CONNECTING -> "正在连接";
      case UP -> "正常";
      case DEGRADED -> "异常";
      case UNAUTHORIZED -> "认证失败";
      case DOWN -> "不可达";
      case NOT_CONFIGURED -> "未配置";
    };
  }
}
