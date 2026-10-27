package com.example.datacraft.desktop.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows DPAPI ({@code CryptProtectData} / {@code CryptUnprotectData}) through the FFM API: the
 * ciphertext can only be opened by the same Windows user on the same machine. An app-specific
 * entropy keeps other DPAPI users of the account from opening it by accident.
 *
 * <p>Calling native code is restricted: javac warns about every such call, and the JVM allows it
 * because the launcher grants {@code --enable-native-access=ALL-UNNAMED}. This class is the only
 * native caller of the app, so the warning is suppressed here and nowhere else.
 */
@SuppressWarnings("restricted")
final class DpapiVault implements SecretVault {

  private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;
  private static final byte[] ENTROPY = "datacraft-desktop/v1".getBytes(StandardCharsets.US_ASCII);

  /** {@code DATA_BLOB}: a DWORD length, padding, then the pointer. */
  private static final StructLayout BLOB =
      MemoryLayout.structLayout(
          JAVA_INT.withName("cbData"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pbData"));

  private static final VarHandle SIZE =
      BLOB.varHandle(MemoryLayout.PathElement.groupElement("cbData"));
  private static final VarHandle DATA =
      BLOB.varHandle(MemoryLayout.PathElement.groupElement("pbData"));

  private final MethodHandle protect;
  private final MethodHandle unprotect;
  private final MethodHandle localFree;

  DpapiVault() {
    Linker linker = Linker.nativeLinker();
    SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
    SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    FunctionDescriptor signature =
        FunctionDescriptor.of(
            JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    protect = linker.downcallHandle(crypt32.findOrThrow("CryptProtectData"), signature);
    unprotect = linker.downcallHandle(crypt32.findOrThrow("CryptUnprotectData"), signature);
    localFree =
        linker.downcallHandle(
            kernel32.findOrThrow("LocalFree"), FunctionDescriptor.of(ADDRESS, ADDRESS));
  }

  @Override
  public byte[] seal(byte[] plain) {
    return call(protect, plain, "protect");
  }

  @Override
  public byte[] open(byte[] sealed) {
    return call(unprotect, sealed, "open");
  }

  @Override
  public String description() {
    return "Windows DPAPI";
  }

  private byte[] call(MethodHandle function, byte[] input, String action) {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment in = blob(arena, input);
      MemorySegment entropy = blob(arena, ENTROPY);
      MemorySegment out = arena.allocate(BLOB);
      int ok =
          (int)
              function.invokeExact(
                  in,
                  MemorySegment.NULL,
                  entropy,
                  MemorySegment.NULL,
                  MemorySegment.NULL,
                  CRYPTPROTECT_UI_FORBIDDEN,
                  out);
      if (ok == 0) {
        throw new SecretVault.VaultException("DPAPI could not " + action + " the secret", null);
      }
      int size = (int) SIZE.get(out, 0L);
      MemorySegment data = ((MemorySegment) DATA.get(out, 0L)).reinterpret(size);
      try {
        return data.toArray(JAVA_BYTE);
      } finally {
        MemorySegment ignored = (MemorySegment) localFree.invokeExact(data);
      }
    } catch (SecretVault.VaultException exception) {
      throw exception;
    } catch (Throwable failure) {
      throw new SecretVault.VaultException("DPAPI could not " + action + " the secret", failure);
    }
  }

  private static MemorySegment blob(Arena arena, byte[] bytes) {
    MemorySegment data = arena.allocate(Math.max(1, bytes.length));
    MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
    MemorySegment blob = arena.allocate(BLOB);
    SIZE.set(blob, 0L, bytes.length);
    DATA.set(blob, 0L, data);
    return blob;
  }
}
