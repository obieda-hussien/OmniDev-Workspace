package com.omnidev.workspace.domain.neural;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Optional host agent: shallow Instrumentation sizes over the index-only reachable graph. */
public final class QueryMemoryFootprintAgent {
  private static Instrumentation instrumentation;

  public static void premain(String args, Instrumentation value) {
    instrumentation = value;
  }

  public static void main(String[] args) throws Exception {
    if (instrumentation == null) throw new IllegalStateException("Requires -javaagent");
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    SupportQueryMemory model =
        TrainedQueryMemory.createCandidate(
            docs, QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv")));
    List<String> roots = new ArrayList<>();
    Deque<Object> pending = new ArrayDeque<>();
    for (Field field : SupportQueryMemory.class.getDeclaredFields()) {
      if (Arrays.asList(
              "words",
              "characters",
              "tokens",
              "tokenExamples",
              "exampleTopics",
              "examples",
              "exampleGroups")
          .contains(field.getName())) {
        field.setAccessible(true);
        pending.add(field.get(model));
        roots.add(field.getName());
      }
    }
    if (!roots.contains("words") || !roots.contains("characters"))
      throw new AssertionError("Index roots missing");
    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<String, Integer> classes = new TreeMap<>();
    long bytes = 0;
    while (!pending.isEmpty()) {
      Object object = pending.removeFirst();
      if (!visited.add(object)) continue;
      Class<?> type = object.getClass();
      classes.put(type.getName(), classes.getOrDefault(type.getName(), 0) + 1);
      bytes += instrumentation.getObjectSize(object);
      if (type.isArray()) {
        if (!type.getComponentType().isPrimitive())
          for (Object value : (Object[]) object) if (value != null) pending.add(value);
      } else {
        for (Class<?> parent = type; parent != null; parent = parent.getSuperclass())
          for (Field field : parent.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(
                true); // Missing module opens fail the audit, never undercount silently.
            Object value = field.get(object);
            if (value != null) pending.add(value);
          }
      }
    }
    StringBuilder counts = new StringBuilder("{");
    for (Map.Entry<String, Integer> entry : classes.entrySet()) {
      if (counts.length() > 1) counts.append(',');
      counts.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
    }
    counts.append('}');
    String json =
        "{\"host_only\":true,\"physical_device_tested\":false,\"index_roots\":\""
            + roots
            + "\",\"reachable_index_objects\":"
            + visited.size()
            + ",\"reachable_index_shallow_bytes\":"
            + bytes
            + ",\"whole_model_heap_measured\":false,\"peak_heap_measured\":false,\"class_counts\":"
            + counts
            + "}";
    Files.write(Paths.get(args[0]), json.getBytes(StandardCharsets.UTF_8));
    System.out.println(json);
  }
}
