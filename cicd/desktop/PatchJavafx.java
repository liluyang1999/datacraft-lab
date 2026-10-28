import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Builds a private JavaFX module path with a disposal guard in Accessible. Never changes Maven's
 * cache. See docs/desktop/javafx-patch.md for provenance, the equivalent source diff and removal.
 */
public final class PatchJavafx {
  private static final String VERSION = "25.0.5";
  private static final String ENTRY = "com/sun/glass/ui/Accessible.class";
  private static final String ORIGINAL =
      "fb5dc4cd2c8a8e3b1e495f8e5a58a098d679686b31e1bcae4f4eed05eef09ffb";
  private static final ClassDesc ACCESSIBLE = ClassDesc.of("com.sun.glass.ui.Accessible");
  private static final ClassDesc HANDLER = ClassDesc.of("com.sun.glass.ui.Accessible$EventHandler");

  private PatchJavafx() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: PatchJavafx.java INPUT_MODULES OUTPUT_MODULES");
    }
    Path input = Path.of(args[0]).toRealPath();
    Path output = Path.of(args[1]).toAbsolutePath().normalize();
    if (input.equals(output)) {
      throw new IllegalArgumentException("The original modules must remain unchanged");
    }
    Files.createDirectories(output);
    int modules = 0;
    try (var paths = Files.list(input)) {
      for (Path jar :
          paths.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
        try (JarFile source = new JarFile(jar.toFile())) {
          if (source.getEntry("module-info.class") == null) {
            continue; // Maven also supplies empty, classifier-free placeholder jars.
          }
          if (!jar.getFileName()
              .toString()
              .matches(
                  "javafx-(base|graphics|controls)-"
                      + VERSION.replace(".", "\\.")
                      + "-(win|linux|mac)(-aarch64)?\\.jar")) {
            throw new IOException("Unreviewed JavaFX module: " + jar.getFileName());
          }
          Path destination = output.resolve(jar.getFileName());
          if (source.getEntry(ENTRY) == null) {
            Files.copy(
                jar,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
          } else {
            patch(source, destination);
          }
          modules++;
        }
      }
    }
    if (modules != 3) {
      throw new IOException("Expected exactly three JavaFX modules, found " + modules);
    }
    System.out.println(
        "JavaFX " + VERSION + ": verified and guarded disposed accessibility callbacks");
  }

  private static void patch(JarFile source, Path destination) throws Exception {
    byte[] original;
    try (var in = source.getInputStream(source.getJarEntry(ENTRY))) {
      original = in.readAllBytes();
    }
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
    if (!ORIGINAL.equals(digest)) {
      throw new IOException("Unreviewed Accessible.class SHA-256: " + digest);
    }
    ClassFile classes = ClassFile.of();
    int[] changed = {0};
    byte[] patched =
        classes.transformClass(
            classes.parse(original),
            (builder, element) -> {
              if (element instanceof MethodModel method
                  && (method.methodName().equalsString("getAttribute")
                      || method.methodName().equalsString("executeAction"))) {
                changed[0]++;
                builder.transformMethod(
                    method,
                    MethodTransform.transformingCode(
                        guard(method.methodName().equalsString("executeAction"))));
              } else {
                builder.with(element);
              }
            });
    if (changed[0] != 2 || !classes.verify(patched).isEmpty()) {
      throw new IOException("The accessibility patch did not produce two valid guarded methods");
    }
    Path temporary = Files.createTempFile(destination.getParent(), "javafx-", ".jar.tmp");
    try {
      try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary))) {
        var entries = source.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          JarEntry copy = new JarEntry(entry.getName());
          copy.setTime(entry.getTime());
          out.putNextEntry(copy);
          if (ENTRY.equals(entry.getName())) {
            out.write(patched);
          } else {
            try (var in = source.getInputStream(entry)) {
              in.transferTo(out);
            }
          }
          out.closeEntry();
        }
        JarEntry marker = new JarEntry("META-INF/datacraft-javafx-patch.properties");
        marker.setTime(0);
        out.putNextEntry(marker);
        out.write(
            ("patch=accessible-disposal-v1\noriginal.sha256="
                    + ORIGINAL
                    + "\npatched.sha256="
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patched))
                    + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
      }
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static CodeTransform guard(boolean action) {
    return new CodeTransform() {
      @Override
      public void atStart(CodeBuilder code) {
        var live = code.newLabel();
        code.aload(0).getfield(ACCESSIBLE, "eventHandler", HANDLER).ifnonnull(live);
        if (action) {
          code.return_();
        } else {
          code.aconst_null().areturn();
        }
        code.labelBinding(live);
      }

      @Override
      public void accept(CodeBuilder code, CodeElement element) {
        code.with(element);
      }
    };
  }
}
