import com.omnidev.workspace.domain.neural.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Goldens for the actual selected utility policy, teacher preservation and variable output schemas.
 */
public final class UtilityAndroidFixtureExporter {
  public static void main(String[] args) throws Exception {
    Path assets = Paths.get(args[0], "assets");
    List<String> texts = new ArrayList<>();
    List<String> old =
        Files.readAllLines(assets.resolve("residual-probes.tsv"), StandardCharsets.UTF_8);
    for (String row : old.subList(1, old.size())) texts.add(row.split("\t", -1)[1]);
    List<String> reserved =
        Files.readAllLines(
            Paths.get("neural-core/fixtures/utility-expert/fresh.tsv"), StandardCharsets.UTF_8);
    for (String row : reserved.subList(1, reserved.size())) texts.add(row.split("\t", -1)[5]);
    if (texts.size() != 995) throw new AssertionError("Utility Android probe count changed");
    RichTextEncoder encoder = TrainedRichVocabulary.encoder();
    TinyNeuralRuntime network = TrainedUtilityExpert.model(),
        presence = TrainedRichPresence.model();
    GatedTextIntentModel base =
        new GatedTextIntentModel(
            TrainedTextIntentCheckpoint.model(),
            TrainedTextIntentCheckpoint.labels(),
            TrainedTextIntentCheckpoint.TEMPERATURE,
            TrainedRefusalGateCheckpoint.model(),
            TrainedRefusalGateCheckpoint.TEMPERATURE,
            TrainedRefusalGateCheckpoint.CUTOFF,
            BilingualTextEncoder.VERSION);
    RichIntentRefusalModel teacher =
        new RichIntentRefusalModel(
            base,
            encoder,
            presence,
            TrainedRichVocabulary.THRESHOLD,
            TrainedRichVocabulary.REQUIRE_MATCH,
            RichTextEncoder.VERSION,
            TrainedPublicRefusal.VOCABULARY_SHA256,
            TrainedPublicRefusal.model(),
            TrainedPublicRefusal.CUTOFF);
    UtilityExpertDecisionModel selected =
        new UtilityExpertDecisionModel(
            teacher,
            encoder,
            network,
            TrainedUtilityExpert.TEMPERATURE,
            TrainedUtilityExpert.reject(),
            TrainedUtilityExpert.change(),
            TrainedUtilityExpert.support(),
            TrainedUtilityExpert.VOCABULARY_SHA256);
    TinyDecisionModel expert =
        new TinyDecisionModel(
            network, RichTextDecisionModel.labels(), TrainedUtilityExpert.TEMPERATURE);
    StringBuilder out =
        new StringBuilder(
            "source\t"
                + TrainedUtilityExpert.SOURCE
                + "\nvocabulary_sha256\t"
                + TrainedUtilityExpert.VOCABULARY_SHA256
                + "\ntemperature\t"
                + TrainedUtilityExpert.TEMPERATURE
                + "\nreject\t"
                + Arrays.toString(TrainedUtilityExpert.reject())
                + "\nchange\t"
                + Arrays.toString(TrainedUtilityExpert.change())
                + "\nsupport\t"
                + Arrays.toString(TrainedUtilityExpert.support())
                + "\n"
                + "id\ttext\tfeature_sha256\texpert_probabilities\tsupport_scores\tteacher_status"
                + "\tteacher_choice\tteacher_probabilities\tutility_status\tutility_choice"
                + "\tutility_probabilities\n");
    for (int i = 0; i < texts.size(); i++) {
      String text = texts.get(i);
      float[] features = encoder.encode(text);
      out.append(i)
          .append('\t')
          .append(text)
          .append('\t')
          .append(AndroidFixtureExporter.featureHash(features))
          .append('\t');
      doubles(out, expert.decide(features, 0, 0).probabilitiesCopy());
      out.append('\t');
      float[] scores = presence.infer(features);
      for (int j = 0; j < 5; j++) {
        if (j > 0) out.append(',');
        out.append(scores[j]);
      }
      decision(out, teacher.classify(text, .6, .15));
      decision(out, selected.classify(text, .6, .15));
      out.append('\n');
    }
    Files.write(
        assets.resolve("utility-probes.tsv"), out.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void decision(StringBuilder out, TinyDecisionModel.Decision d) {
    out.append('\t')
        .append(d.status)
        .append('\t')
        .append(d.choice == null ? "-" : d.choice)
        .append('\t');
    doubles(out, d.probabilitiesCopy());
  }

  private static void doubles(StringBuilder out, double[] values) {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) out.append(',');
      out.append(values[i]);
    }
  }
}
