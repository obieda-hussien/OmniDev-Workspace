package com.omnidev.workspace.domain.neural;

import java.nio.charset.StandardCharsets;
import java.security.*;

/** Immutable bounded domain support memory. Similarity is neither correctness nor authorization. */
public final class DomainPrototypeSupport {
  public static final String VERSION = "group-domain-language-centroid-v1";
  public static final int DIMENSIONS = 1024, MAX_PROTOTYPES = 15;
  private final float[] vectors;
  private final int[] owners;
  private final String vocabularyFingerprint, fingerprint;

  public DomainPrototypeSupport(
      float[] vectors, int[] owners, String vocabularyFingerprint, String version) {
    if (vectors == null
        || owners == null
        || owners.length < 5
        || owners.length > MAX_PROTOTYPES
        || vectors.length != owners.length * DIMENSIONS
        || vocabularyFingerprint == null
        || !vocabularyFingerprint.matches("[0-9a-f]{64}")
        || !VERSION.equals(version)) throw new IllegalArgumentException("Invalid prototype schema");
    this.vectors = vectors.clone();
    this.owners = owners.clone();
    this.vocabularyFingerprint = vocabularyFingerprint;
    vectors = this.vectors;
    owners = this.owners;
    int[] counts = new int[5];
    for (int p = 0; p < owners.length; p++) {
      if (owners[p] < 0 || owners[p] >= 5 || ++counts[owners[p]] > 3)
        throw new IllegalArgumentException("Invalid prototype owner/budget");
      double norm = 0;
      for (int i = 0; i < DIMENSIONS; i++) {
        float v = vectors[p * DIMENSIONS + i];
        if (!Float.isFinite(v) || Math.abs(v) > 1)
          throw new IllegalArgumentException("Invalid prototype value");
        norm += (double) v * v;
      }
      if (Math.abs(norm - 1) > .002)
        throw new IllegalArgumentException("Prototype must be unit normalized");
    }
    for (int count : counts)
      if (count == 0) throw new IllegalArgumentException("Missing prototype domain");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(VERSION.getBytes(StandardCharsets.UTF_8));
      digest.update(vocabularyFingerprint.getBytes(StandardCharsets.UTF_8));
      for (int owner : owners) digest.update((byte) owner);
      for (float value : vectors) {
        int bits = Float.floatToIntBits(value);
        digest.update((byte) (bits >>> 24));
        digest.update((byte) (bits >>> 16));
        digest.update((byte) (bits >>> 8));
        digest.update((byte) bits);
      }
      StringBuilder text = new StringBuilder();
      for (byte b : digest.digest()) {
        text.append(Character.forDigit((b >>> 4) & 15, 16));
        text.append(Character.forDigit(b & 15, 16));
      }
      fingerprint = text.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public float similarity(float[] features, int domain) {
    if (features == null || features.length != DIMENSIONS || domain < 0 || domain >= 5)
      throw new IllegalArgumentException("Invalid prototype query");
    double norm = 0;
    for (float value : features) {
      if (!Float.isFinite(value) || Math.abs(value) > 1)
        throw new IllegalArgumentException("Invalid prototype features");
      norm += (double) value * value;
    }
    if (norm == 0) return 0;
    norm = Math.sqrt(norm);
    double best = 0;
    for (int p = 0; p < owners.length; p++)
      if (owners[p] == domain) {
        double dot = 0;
        for (int i = 0; i < DIMENSIONS; i++)
          if (features[i] != 0) dot += (double) vectors[p * DIMENSIONS + i] * features[i];
        best = Math.max(best, dot / norm);
      }
    return (float) Math.min(1, best);
  }

  public String vocabularyFingerprint() {
    return vocabularyFingerprint;
  }

  public String fingerprint() {
    return fingerprint;
  }

  public int prototypeCount() {
    return owners.length;
  }

  public int floatCount() {
    return vectors.length;
  }
}
