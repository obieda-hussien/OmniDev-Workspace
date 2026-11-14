package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * Canonical length-prefixed immutable checkpoint identity, independent of sparse execution mode.
 */
final class CheckpointFingerprint {
  private final MessageDigest digest;

  CheckpointFingerprint(String kind) {
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    text(kind);
  }

  CheckpointFingerprint text(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    integer(bytes.length);
    digest.update(bytes);
    return this;
  }

  CheckpointFingerprint integer(int value) {
    for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    return this;
  }

  CheckpointFingerprint number(double value) {
    long bits = Double.doubleToLongBits(value);
    for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (bits >>> shift));
    return this;
  }

  CheckpointFingerprint floats(float[] values) {
    integer(values.length);
    for (float v : values) integer(Float.floatToIntBits(v));
    return this;
  }

  String finish() {
    StringBuilder out = new StringBuilder();
    for (byte b : digest.digest()) {
      out.append(Character.forDigit((b >>> 4) & 15, 16));
      out.append(Character.forDigit(b & 15, 16));
    }
    return out.toString();
  }
}
