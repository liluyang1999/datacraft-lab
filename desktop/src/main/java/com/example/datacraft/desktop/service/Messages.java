package com.example.datacraft.desktop.service;

import com.example.datacraft.desktop.api.ApiException;
import java.util.regex.Pattern;

/**
 * The words the user reads when something did not work: what happened and what to do, in the
 * interface's voice. One place, so the same failure reads the same in a toast and in the log.
 */
public final class Messages {

  private static final Pattern HAN_THEN_LATIN = Pattern.compile("(\\p{IsHan})([A-Za-z0-9])");
  private static final Pattern LATIN_THEN_HAN = Pattern.compile("([A-Za-z0-9])(\\p{IsHan})");

  private Messages() {}

  public static String unauthorized(String backend) {
    return backend.startsWith("Airflow") ? "Airflow 用户名或口令不正确：在设置里检查连接。" : "引擎令牌不正确或缺失：在设置里更新令牌。";
  }

  /** A failed request, as one sentence with its next step. */
  public static String describe(String backend, ApiException failure) {
    String detail = failure.detail().isBlank() ? "" : "（" + failure.detail() + "）";
    String sentence =
        switch (failure.error()) {
          case UNREACHABLE -> "无法连接" + backend + detail + "。确认服务已启动，或在设置里检查地址。";
          case TIMED_OUT -> backend + "没有在时限内回答" + detail + "。它可能仍在处理：稍后刷新查看。";
          case UNAUTHORIZED -> unauthorized(backend);
          case FORBIDDEN -> backend + "拒绝了这个操作" + detail + "：账号没有对应权限。";
          case NOT_FOUND -> backend + "上已经没有这一项" + detail + "，可能已被删除：刷新后再试。";
          case BUSY -> "引擎的运行数已满，没有开始运行。稍后再试。";
          case REJECTED -> backend + "拒绝了这个请求" + detail + "。检查参数后再试。";
          case SERVER -> backend + "内部出错（HTTP " + failure.status() + "）" + detail + "。查看服务日志。";
          case PROTOCOL -> backend + "的响应无法识别" + detail + "。确认地址指向的是正确的服务。";
        };
    return spaced(sentence);
  }

  /** Puts a space between Chinese characters and Latin letters or digits, as the UI text does. */
  public static String spaced(String text) {
    String once = HAN_THEN_LATIN.matcher(text).replaceAll("$1 $2");
    return LATIN_THEN_HAN.matcher(once).replaceAll("$1 $2");
  }
}
