import com.omnidev.workspace.neural.probe.NeuralProbeContract;
import com.omnidev.workspace.neural.probe.PublicNeuralProbeContract;
import com.omnidev.workspace.neural.probe.ResidualNeuralProbeContract;
import com.omnidev.workspace.neural.probe.UtilityNeuralProbeContract;
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
        try (Reader rich = Files.newBufferedReader(Paths.get(args[2]), StandardCharsets.UTF_8)) {
            NeuralProbeContract.verifyRich(NeuralProbeContract.read(new StringReader(fixture)), rich);
        }
        reject(fixture.replaceFirst("[0-9a-f]{64}", "0000000000000000000000000000000000000000000000000000000000000000"));
        reject(fixture.substring(0, fixture.lastIndexOf('\n', fixture.length() - 2) + 1));
        reject(fixture.replaceFirst("[0-9a-f]{64}", "invalid-hash"));
        reject(fixture.replaceFirst("CHOICE", "FORGED_SUCCESS"));
        String publicFixture = new String(Files.readAllBytes(Paths.get(args[3])), StandardCharsets.UTF_8);
        PublicNeuralProbeContract.verify(new StringReader(publicFixture));
        rejectPublic(publicFixture.replaceFirst("[0-9a-f]{64}", "0000000000000000000000000000000000000000000000000000000000000000"));
        rejectPublic(publicFixture.substring(0, publicFixture.lastIndexOf('\n', publicFixture.length() - 2) + 1));
        rejectPublic(publicFixture.replaceFirst("CHOICE", "FORGED_SUCCESS"));
        String residualFixture = new String(Files.readAllBytes(Paths.get(args[4])), StandardCharsets.UTF_8);
        ResidualNeuralProbeContract.verify(new StringReader(residualFixture));
        rejectResidual(residualFixture.replaceFirst("[0-9a-f]{64}", "0000000000000000000000000000000000000000000000000000000000000000"));
        rejectResidual(residualFixture.substring(0, residualFixture.lastIndexOf('\n', residualFixture.length() - 2) + 1));
        rejectResidual(residualFixture.replaceFirst("CHOICE", "FORGED_SUCCESS"));
        String utilityFixture=new String(Files.readAllBytes(Paths.get(args[5])),StandardCharsets.UTF_8);
        UtilityNeuralProbeContract.verify(new StringReader(utilityFixture));
        rejectUtility(utilityFixture.replaceFirst("[0-9a-f]{64}","0000000000000000000000000000000000000000000000000000000000000000"));
        rejectUtility(utilityFixture.substring(0,utilityFixture.lastIndexOf('\n',utilityFixture.length()-2)+1));
        rejectUtility(utilityFixture.replaceFirst("CHOICE","FORGED_SUCCESS"));
        rejectUtility(utilityFixture.replaceFirst("temperature\\t[^\\n]+","temperature\t99"));
        System.out.println("Android host probes: 139 original + 699 public + 859 residual + 995 utility cases and 14 corrupt-fixture rejections passed; ART execution still required.");
    }
    private static void rejectPublic(String text) throws Exception { try { PublicNeuralProbeContract.verify(new StringReader(text)); } catch (IllegalStateException expected) { return; } throw new AssertionError("Corrupt public probe accepted"); }
    private static void rejectResidual(String text) throws Exception { try { ResidualNeuralProbeContract.verify(new StringReader(text)); } catch (IllegalStateException expected) { return; } throw new AssertionError("Corrupt residual probe accepted"); }
    private static void rejectUtility(String text)throws Exception{try{UtilityNeuralProbeContract.verify(new StringReader(text));}catch(IllegalStateException expected){return;}throw new AssertionError("Corrupt utility probe accepted");}
    private static void reject(String fixture) throws Exception {
        try { NeuralProbeContract.verify(NeuralProbeContract.read(new StringReader(fixture))); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Corrupted probe accepted");
    }
}
