import com.omnidev.workspace.neural.probe.NeuralProbeContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Exercise the actual instrumentation contract on the host before packaging its test APK. */
public final class AndroidProbeSmoke {
    public static void main(String[] args) throws Exception {
        String fixture = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        NeuralProbeContract.verify(NeuralProbeContract.read(new StringReader(fixture)));
        try (Reader cascade = Files.newBufferedReader(Paths.get(args[1]), StandardCharsets.UTF_8)) {
            NeuralProbeContract.verifyCascade(NeuralProbeContract.read(new StringReader(fixture)), cascade);
        }
        reject(fixture.replaceFirst("[0-9a-f]{64}", "0000000000000000000000000000000000000000000000000000000000000000"));
        reject(fixture.substring(0, fixture.lastIndexOf('\n', fixture.length() - 2) + 1));
        reject(fixture.replaceFirst("[0-9a-f]{64}", "invalid-hash"));
        reject(fixture.replaceFirst("CHOICE", "FORGED_SUCCESS"));
        System.out.println("Android probe contract: 139 original/cascade host parity cases + 4 corrupt-fixture rejections passed; ART execution still required.");
    }
    private static void reject(String fixture) throws Exception {
        try { NeuralProbeContract.verify(NeuralProbeContract.read(new StringReader(fixture))); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Corrupted probe accepted");
    }
}
