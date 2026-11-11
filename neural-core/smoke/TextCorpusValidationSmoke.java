import com.omnidev.workspace.domain.neural.TextIntentExperiment;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the actual experiment loader before training can consume contaminated data. */
public final class TextCorpusValidationSmoke {
    public static void main(String[] args) throws Exception {
        List<String> original = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        TextIntentExperiment.main(new String[] {args[0], "--validate"});
        List<String> changed = new ArrayList<>(original);
        changed.set(1, changed.get(1).replaceFirst("train", "holdout"));
        rejected(changed);
        changed = new ArrayList<>(original);
        String[] first = changed.get(1).split("\t");
        changed.add("holdout\tduplicate-task\trepository\ten\t" + first[4].toUpperCase(Locale.ROOT));
        rejected(changed);
        changed = new ArrayList<>(original);
        changed.set(1, changed.get(1).replace("\trepository\t", "\tunknown\t"));
        rejected(changed);
        changed = new ArrayList<>(original);
        changed.removeIf(line -> line.startsWith("development\t") && line.contains("\trepository\t"));
        rejected(changed);
        System.out.println("Corpus contamination and missing-class regressions passed");
    }

    private static void rejected(List<String> rows) throws Exception {
        Path path = Files.createTempFile("omni-corpus-check-", ".tsv");
        try {
            Files.write(path, rows, StandardCharsets.UTF_8);
            try { TextIntentExperiment.main(new String[] {path.toString(), "--validate"}); }
            catch (IllegalArgumentException expected) { return; }
            throw new AssertionError("Contaminated corpus was accepted");
        } finally { Files.deleteIfExists(path); }
    }
}
