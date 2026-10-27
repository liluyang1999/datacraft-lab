package com.example.datacraft.desktop.api;

/** Why a request to a backend did not produce what was asked for. */
public enum ApiError {
  /**
   * Connection refused, unknown host, network down, or no connection within the connect timeout.
   */
  UNREACHABLE,
  /**
   * The request was sent but no answer came within its time limit. The server may still be working
   * on it: a job run that outlives the wait limit goes on on the engine.
   */
  TIMED_OUT,
  /** 401: the token, user name or password is missing or wrong. */
  UNAUTHORIZED,
  /** 403: the account may not do this, or the engine refused a cross-origin request. */
  FORBIDDEN,
  /** 404: no such job, DAG, run or task. */
  NOT_FOUND,
  /** 503 from the engine: every run slot is taken; nothing ran. */
  BUSY,
  /** 400, 409 or 422: the request was rejected; the server's explanation is in the detail. */
  REJECTED,
  /** Any other 5xx without a usable body. */
  SERVER,
  /** An answer that is not the documented JSON. */
  PROTOCOL
}
