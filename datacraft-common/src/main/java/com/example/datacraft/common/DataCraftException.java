package com.example.datacraft.common;

public class DataCraftException extends RuntimeException {

  public DataCraftException(String message) {
    super(message);
  }

  public DataCraftException(String message, Throwable cause) {
    super(message, cause);
  }
}
