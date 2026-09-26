package com.example.datacraft.common;

import java.io.Serial;

public class DataCraftException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public DataCraftException(String message) {
    super(message);
  }

  public DataCraftException(String message, Throwable cause) {
    super(message, cause);
  }
}
