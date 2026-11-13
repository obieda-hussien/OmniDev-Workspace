import com.omnidev.workspace.domain.neural.DecisionChallengeExperiment;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DecisionChallengeValidationSmoke {
    public static void main(String[] args) throws Exception {
        List<String> rows = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        List<String> changed = new ArrayList<>(rows);
        changed.add(rows.get(1));
        rejects(changed, args[1]);
        changed = new ArrayList<>(rows);
        changed.removeIf(line -> line.contains("\tambiguous\t"));
        rejects(changed, args[1]);
        changed = new ArrayList<>(rows);
        changed.set(1, rows.get(1).replace("\tabstain\t", "\tchat\t"));
        rejects(changed, args[1]);
        changed = new ArrayList<>(rows);
        String original = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8).get(1).split("\t")[4];
        changed.set(1, "duplicate-base\tknown\ten\trepository\t" + original);
        rejects(changed, args[1]);
        System.out.println("Challenge expectation and contamination guards passed");
    }
    private static void rejects(List<String> rows, String original) throws Exception {
        Path temporary = Files.createTempFile("omni-challenge-check-", ".tsv");
        try {
            Files.write(temporary, rows, StandardCharsets.UTF_8);
            try { DecisionChallengeExperiment.main(new String[] {temporary.toString(), original}); }
            catch (IllegalArgumentException expected) { return; }
            throw new AssertionError("Invalid challenge corpus accepted");
        } finally { Files.deleteIfExists(temporary); }
    }
}
