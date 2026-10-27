package com.example.datacraft.desktop.api;

import java.util.Optional;

/**
 * A failed request: the kind of failure, the HTTP status when there was one, and the server's own
 * explanation when it gave one. The message never contains a token or password.
 */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ApiError error;
  private final int status;
  private final String detail;

  public ApiException(ApiError error, int status, String detail, Throwable cause) {
    super(
        error
            + (status > 0 ? " (HTTP " + status + ")" : "")
            + (detail.isBlank() ? "" : ": " + detail),
        cause);
    this.error = error;
    this.status = status;
    this.detail = detail;
  }

  public ApiException(ApiError error, int status, String detail) {
    this(error, status, detail, null);
  }

  public ApiError error() {
    return error;
  }

  /** The HTTP status, or 0 when no response arrived. */
  public int status() {
    return status;
  }

  /** The server's explanation or the transport's cause, possibly empty. */
  public String detail() {
    return detail;
  }

  /** The {@link ApiException} inside a future's failure, if that is what it is. */
  public static Optional<ApiException> of(Throwable failure) {
    Throwable cause = failure;
    while (cause != null) {
      if (cause instanceof ApiException api) {
        return Optional.of(api);
      }
      cause = cause.getCause();
    }
    return Optional.empty();
  }
}
