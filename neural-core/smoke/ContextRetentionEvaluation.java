package com.omnidev.workspace.domain.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class ContextRetentionEvaluation {
  public static void main(String[] args) throws Exception {
    List<String> negatives = new ArrayList<>();
    List<String> positives = new ArrayList<>();
    for (String row : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
      String[] fields = row.split("\t", 3);
      if (!fields[0].equals("test")) continue;
      (fields[1].equals("keep") ? positives : negatives).add(fields[2]);
    }
    int correct = 0;
    for (String positive : positives)
      for (String negative : negatives)
        if (ContextRetention.score(positive) > ContextRetention.score(negative)) correct++;
    int pairs = positives.size() * negatives.size();
    if (pairs == 0 || correct * 100 < 80 * pairs)
      throw new AssertionError("Context ranking scored " + correct + "/" + pairs);

    int saved = 0;
    for (int shift = 0; shift < negatives.size(); shift++) {
      List<String> history = new ArrayList<>();
      for (int i = 0; i < 200; i++)
        history.add(negatives.get((i + shift) % negatives.size()) + " " + i);
      history.addAll(78, positives);
      String baseline = String.join("\n", history).substring(0, 500);
      String digest = ContextRetention.select(history, 500);
      if (digest.length() > 500) throw new AssertionError("Budget exceeded");
      int previous = -1;
      for (String excerpt : digest.split("\n")) {
        int index = history.indexOf(excerpt);
        if (index <= previous) throw new AssertionError("Broken order or partial excerpt");
        previous = index;
      }
      for (String positive : positives) {
        if (baseline.contains(positive)) throw new AssertionError("Baseline unexpectedly retained middle evidence");
        if (digest.contains(positive)) saved++;
      }
    }
    int expected = positives.size() * negatives.size();
    if (saved * 100 < expected * 80) throw new AssertionError("Missed too many important events");
    System.out.println("Pairwise ranking " + correct + "/" + pairs);
    System.out.println("Budgeted replay " + saved + "/" + expected + " retained (prefix baseline 0/" + expected + ")");
  }
}
