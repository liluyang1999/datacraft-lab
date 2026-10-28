import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javafx.css.CssParser;
import javafx.css.Stylesheet;

/** Precompiles the three application stylesheets with the exact JavaFX version used at runtime. */
public final class CompileCss {
  private CompileCss() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: CompileCss.java THEME_RESOURCES");
    }
    Path directory = Path.of(args[0]).toRealPath();
    for (String name : List.of("base", "light", "dark")) {
      Path source = directory.resolve(name + ".css");
      Path binary = directory.resolve(name + ".bss");
      CssParser.errorsProperty().clear();
      Stylesheet.convertToBinary(source.toFile(), binary.toFile());
      if (!CssParser.errorsProperty().isEmpty()) {
        throw new IOException("Invalid stylesheet " + source + ": " + CssParser.errorsProperty());
      }
      Stylesheet loaded = Stylesheet.loadBinary(binary.toUri().toURL());
      if (loaded == null || loaded.getRules().isEmpty()) {
        throw new IOException("Empty compiled stylesheet: " + binary);
      }
    }
    System.out.println("Compiled and reloaded all three JavaFX stylesheets");
  }
}
