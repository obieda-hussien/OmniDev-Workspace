import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Goldens for the bounded support bank; includes all old utility probes and the frozen authored
 * set.
 */
public final class PrototypeAndroidFixtureExporter {
  public static void main(String[] args) throws Exception {
    Path assets = Paths.get(args[0], "assets");
    List<String> texts = new ArrayList<>();
    List<String> old =
        Files.readAllLines(assets.resolve("utility-probes.tsv"), StandardCharsets.UTF_8);
    for (String row : old.subList(7, old.size())) texts.add(row.split("\t", -1)[1]);
    List<String> authored =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/prototype-support/authored.tsv"),
            StandardCharsets.UTF_8);
    for (String row : authored.subList(1, authored.size())) texts.add(row.split("\t", -1)[5]);
    if (texts.size() != 1070) throw new AssertionError("Prototype Android probe count changed");
    RichTextEncoder encoder = TrainedRichVocabulary.encoder();
    DomainPrototypeSupport bank = TrainedDomainPrototypes.model();
    RichIntentRefusalModel teacher =
        new RichIntentRefusalModel(
            new GatedTextIntentModel(
                TrainedTextIntentCheckpoint.model(),
                TrainedTextIntentCheckpoint.labels(),
                TrainedTextIntentCheckpoint.TEMPERATURE,
                TrainedRefusalGateCheckpoint.model(),
                TrainedRefusalGateCheckpoint.TEMPERATURE,
                TrainedRefusalGateCheckpoint.CUTOFF,
                BilingualTextEncoder.VERSION),
            encoder,
            TrainedRichPresence.model(),
            TrainedRichVocabulary.THRESHOLD,
            TrainedRichVocabulary.REQUIRE_MATCH,
            RichTextEncoder.VERSION,
            TrainedPublicRefusal.VOCABULARY_SHA256,
            TrainedPublicRefusal.model(),
            TrainedPublicRefusal.CUTOFF);
    UtilityExpertDecisionModel model =
        new UtilityExpertDecisionModel(
            teacher,
            encoder,
            TrainedUtilityExpert.model(),
            TrainedUtilityExpert.TEMPERATURE,
            TrainedPrototypePolicy.reject(),
            TrainedPrototypePolicy.change(),
            TrainedUtilityExpert.support(),
            TrainedPrototypePolicy.VOCABULARY_SHA256,
            bank,
            TrainedPrototypePolicy.cutoffs(),
            TrainedPrototypePolicy.BANK_SHA256);
    StringBuilder out =
        new StringBuilder(
            "bank_sha256\t"
                + bank.fingerprint()
                + "\nvocabulary_sha256\t"
                + encoder.fingerprint()
                + "\nreject\t"
                + Arrays.toString(TrainedPrototypePolicy.reject())
                + "\nchange\t"
                + Arrays.toString(TrainedPrototypePolicy.change())
                + "\ncutoffs\t"
                + Arrays.toString(TrainedPrototypePolicy.cutoffs())
                + "\nid\ttext\tfeature_sha256\tsupport_scores\tstatus\tchoice\tprobabilities\n");
    for (int i = 0; i < texts.size(); i++) {
      String text = texts.get(i);
      float[] features = encoder.encode(text);
      out.append(i)
          .append('\t')
          .append(text)
          .append('\t')
          .append(AndroidFixtureExporter.featureHash(features))
          .append('\t');
      for (int d = 0; d < 5; d++) {
        if (d > 0) out.append(',');
        out.append(bank.similarity(features, d));
      }
      TinyDecisionModel.Decision decision = model.classify(text, .6, .15);
      out.append('\t')
          .append(decision.status)
          .append('\t')
          .append(decision.choice == null ? "-" : decision.choice)
          .append('\t');
      double[] probabilities = decision.probabilitiesCopy();
      for (int j = 0; j < probabilities.length; j++) {
        if (j > 0) out.append(',');
        out.append(probabilities[j]);
      }
      out.append('\n');
    }
    Files.write(
        assets.resolve("prototype-probes.tsv"), out.toString().getBytes(StandardCharsets.UTF_8));
  }
}
