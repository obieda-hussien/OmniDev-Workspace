import com.omnidev.workspace.domain.history.ChatMetadataModel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public final class OpeningTitleEvaluation {
    public static void main(String[] args) throws Exception {
        Map<String, int[]> totals = new LinkedHashMap<>();
        for (String row : Files.readAllLines(Paths.get(args[0]))) {
            String[] cells = row.split("\t", -1);
            if (cells[0].equals("split") || cells[0].equals("train")) continue;
            String fallback = ChatMetadataModel.analyze(Collections.singletonList(cells[2]), "app", "").title;
            String title = ChatMetadataModel.openingTitle(cells[2], cells[3], fallback);
            String actual = title.equals(fallback) ? "other" : category(title);
            int[] counts = totals.computeIfAbsent(cells[0], ignored -> new int[4]);
            counts[0]++;
            if (cells[1].equals(actual)) counts[1]++;
            if (!cells[1].equals("other") && actual.equals("other")) counts[2]++;
            if (!cells[1].equals(actual) && !actual.equals("other")) counts[3]++;
        }
        totals.forEach((split, c) -> System.out.printf("%s: correct=%d/%d missed=%d wrong_title=%d%n", split, c[1], c[0], c[2], c[3]));
    }

    private static String category(String title) {
        if (title.contains("Gradle")) return "gradle";
        if (title.contains("Git")) return "git";
        if (title.contains("Bluetooth") || title.contains("البلوتوث")) return "bluetooth";
        if (title.contains("WhatsApp") || title.contains("واتساب")) return "notifications";
        if (title.contains("Study") || title.contains("المذاكرة")) return "study";
        throw new AssertionError("Unexpected title: " + title);
    }
}
