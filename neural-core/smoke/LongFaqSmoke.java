package com.omnidev.workspace.domain.neural;

import java.nio.file.*;
import java.util.*;

public final class LongFaqSmoke {
  public static void main(String[] args) throws Exception {
    List<ProductSupportModel.Article> docs =
        ProductSupportExperiment.loadArticles(Paths.get("build/product-support/documents.tsv"));
    SupportContrastiveModel model =
        TrainedContrastiveSupport.createCandidate(
            docs, QueryMemoryExperiment.examples(Paths.get("build/query-memory-v5/exemplars.tsv")));
    LongFaqReader reader = new LongFaqReader(model, docs);
    StringBuilder prefix = new StringBuilder();
    while (prefix.length() < 2400)
      prefix.append(
          "كنت براجع إعدادات التطبيق وكتبت ملاحظات عن تجربتي. I kept a record of my earlier"
              + " session. ");
    int ok = 0, wrong = 0, refused = 0;
    List<Long> times = new ArrayList<>();
    for (ProductSupportModel.Article d : docs) {
      LongFaqReader.Reply shortReply = reader.read(d.question);
      String longInput =
          prefix + (d.language.equals("ar") ? " سؤالي: " : " My question: ") + d.question;
      long start = System.nanoTime();
      LongFaqReader.Reply reply = reader.read(longInput);
      times.add(System.nanoTime() - start);
      if (reply.status != shortReply.status
          || !reply.text.equals(shortReply.text)
          || !reply.sourceId.equals(shortReply.sourceId)) throw new AssertionError(d.id);
      if (reply.status != LongFaqReader.Status.ANSWER) refused++;
      else if (reply.sourceId.startsWith(d.topic + ":")
          && docs.stream()
              .anyMatch(a -> a.id.equals(reply.sourceId) && a.text.contains(reply.text))) ok++;
      else wrong++;
    }
    for (String q :
        Arrays.asList(
            prefix.toString(),
            "سؤالي: أفتح إيه؟ وأقفل إيه؟",
            "لو أنا في لايت. سؤالي: أفتح الوكيل إزاي؟",
            "My question: where? Actually I mean: when?",
            prefix.toString() + prefix + prefix + prefix,
            "قصدي: ")) {
      if (reader.read(q).status != LongFaqReader.Status.CLARIFY)
        throw new AssertionError("Expected clarification");
    }
    if (reader.read(prefix + " قصدي: " + docs.get(0).question).status
        != reader.read(docs.get(0).question).status) throw new AssertionError("Correction marker");
    int proseCorrect = 0, proseWrong = 0, proseAbstain = 0;
    for (String line :
        Files.readAllLines(Paths.get("neural-core/fixtures/quantity-evidence-v15/probes.tsv"))
            .subList(1, 13)) {
      String[] f = line.split("\t", -1);
      for (int i = 3; i < 6; i++) {
        LongFaqReader.Reply r = reader.read(prefix + ", " + f[i]);
        if (r.status != LongFaqReader.Status.ANSWER) proseAbstain++;
        else if (f[2].equals("1") && r.sourceId.startsWith(f[1] + ":")) proseCorrect++;
        else proseWrong++;
      }
    }
    System.out.printf(
        "36 unmarked long queries: correct %d, wrong %d, withheld %d%n",
        proseCorrect, proseWrong, proseAbstain);
    Collections.sort(times);
    System.out.printf(
        Locale.ROOT,
        "58 short/long pairs; correct %d, wrong %d, refused %d; prefix %d UTF16; host p50 %.3f ms,"
            + " p95 %.3f ms%n",
        ok,
        wrong,
        refused,
        prefix.length(),
        times.get(28) / 1e6,
        times.get(55) / 1e6);
  }
}
