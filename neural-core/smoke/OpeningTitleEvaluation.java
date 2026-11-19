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
            else System.out.println(cells[0] + " " + cells[1] + " => " + actual + " : " + cells[2]);
            if (!cells[1].equals("other") && actual.equals("other")) counts[2]++;
            if (!cells[1].equals(actual) && !actual.equals("other")) counts[3]++;
        }
        require("توزيع شفتات المخزن", ChatMetadataModel.openingTitle("ممكن تساعدني في توزيع شفتات المخزن الأسبوع الجاي؟",
                "توزيع شفتات المخزن يبدأ بمعرفة مواعيد كل موظف.", "Fallback"));
        if (args.length > 1) evaluateTopics(args[1]);
        String padding = String.join("", Collections.nCopies(68, "The supporting details are listed below. "));
        require("Room database migration", ChatMetadataModel.openingTitle("Can you take a look?",
                padding + "SQLite schema verification in Room fails after upgrading the database version", "Fallback"));
        require("Fallback", ChatMetadataModel.openingTitle("Can you take a look?",
                "```\n" + padding + "SQLite Room database schema migration", "Fallback"));
        require("Keep original", ChatMetadataModel.openingTitle("Review the rent agreement",
                padding + "SQLite schema verification in Room fails after upgrading the database version", "Keep original"));
        String dominant = ChatMetadataModel.analyze(Arrays.asList("Fix the Kotlin coroutine cancellation in the repository",
                "اربط نوشن مع التطبيقات المتصلة للمزامنة", "Connect linked accounts and sync files between apps"), "app", "").topic;
        require("connected_apps", dominant);
        String oldTopic = "The Bluetooth headset pairing request timed out. ";
        String log = String.join("", Collections.nCopies(410, oldTopic));
        require("Room database migration", ChatMetadataModel.openingTitle("Can you take a look?",
                log + "\nRoot cause: SQLite schema verification in Room fails after upgrading the database version", "Fallback"));
        require("Fallback", ChatMetadataModel.openingTitle("Can you take a look?", log +
                "\nConclusion: Could you provide more details?", "Fallback"));
        List<String> history = new ArrayList<>(Collections.nCopies(10, "Fix the Kotlin coroutine cancellation in the repository"));
        history.add("موضوع جديد رتب جدول مذاكرة قبل الامتحان");
        history.add("Organize my weekly study and revision sessions");
        require("personal", ChatMetadataModel.analyze(history, "app", "").topic);
        totals.forEach((split, c) -> System.out.printf("%s: correct=%d/%d missed=%d wrong_title=%d%n", split, c[1], c[0], c[2], c[3]));
    }

    private static void evaluateTopics(String path) throws Exception {
        int correct = 0;
        int count = 0;
        for (String row : Files.readAllLines(Paths.get(path))) {
            String[] cells = row.split("\t", -1);
            if (!cells[0].equals("test")) continue;
            String actual = ChatMetadataModel.analyze(Collections.singletonList(cells[2]), "app", "").topic;
            if (cells[1].equals(actual)) correct++;
            else System.out.println("Topic mismatch " + cells[1] + " => " + actual + " : " + cells[2]);
            count++;
        }
        System.out.printf("Topics: correct=%d/%d%n", correct, count);
    }

    private static void require(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static String category(String title) {
        if (title.contains("discovery") || title.contains("اكتشاف أدوات")) return "tool_discovery";
        if (title.contains("title generation") || title.contains("عناوين المحادثات")) return "chat_titles";
        if (title.contains("Gradle")) return "gradle";
        if (title.contains("Git")) return "git";
        if (title.contains("Bluetooth") || title.contains("البلوتوث")) return "bluetooth";
        if (title.contains("WhatsApp") || title.contains("واتساب")) return "notifications";
        if (title.contains("Study") || title.contains("المذاكرة")) return "study";
        if (title.contains("Room")) return "room";
        if (title.contains("Compose")) return "compose";
        if (title.contains("APK")) return "apk";
        if (title.contains("battery") || title.contains("بطارية")) return "battery";
        if (title.contains("Wi-Fi") || title.contains("الواي فاي")) return "network";
        throw new AssertionError("Unexpected title: " + title);
    }
}
