package com.example.datacraft.desktop.viewmodel;

import com.example.datacraft.desktop.service.JobCatalog;
import java.util.Optional;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** One editable parameter. Known keys come from the catalogue and cannot be renamed. */
public final class ParamRow {
  private final Optional<JobCatalog.Param> spec;
  private final StringProperty key;
  private final StringProperty value;

  ParamRow(Optional<JobCatalog.Param> spec, String key, String value) {
    this.spec = spec;
    this.key = new SimpleStringProperty(key);
    this.value = new SimpleStringProperty(value);
  }

  /** A known parameter left empty so that the backend's default applies. */
  public static ParamRow known(JobCatalog.Param spec) {
    return new ParamRow(Optional.of(spec), spec.key(), "");
  }

  public static ParamRow custom() {
    return new ParamRow(Optional.empty(), "", "");
  }

  public Optional<JobCatalog.Param> spec() {
    return spec;
  }

  public boolean known() {
    return spec.isPresent();
  }

  public boolean required() {
    return spec.map(JobCatalog.Param::required).orElse(false);
  }

  public StringProperty key() {
    return key;
  }

  public StringProperty value() {
    return value;
  }
}
