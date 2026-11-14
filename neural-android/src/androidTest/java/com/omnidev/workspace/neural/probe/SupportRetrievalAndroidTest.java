package com.omnidev.workspace.neural.probe;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SupportRetrievalAndroidTest {
  @Test
  public void supportCheckpointsPreserveQuotesDecisionsAndDefault() throws Exception {
    android.content.Context context = InstrumentationRegistry.getInstrumentation().getContext();
    int count;
    try (Reader docs =
            new InputStreamReader(
                context.getAssets().open("support-documents.tsv"), StandardCharsets.UTF_8);
        Reader queries =
            new InputStreamReader(
                context.getAssets().open("support-queries.tsv"), StandardCharsets.UTF_8);
        Reader expected =
            new InputStreamReader(
                context.getAssets().open("support-probes.tsv"), StandardCharsets.UTF_8)) {
      count = SupportRetrievalProbeContract.verify(docs, queries, expected);
    }
    JSONObject report =
        new JSONObject()
            .put(
                "run_id",
                InstrumentationRegistry.getArguments()
                    .getString("neuralProbeRun", "manual_untracked"))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("support_parity_passed", true)
            .put("predictions", count)
            .put("queries", 351)
            .put("documents", 58)
            .put("retained_default_checks", 351)
            .put("phone_benchmark", false);
    try (Writer output =
        new OutputStreamWriter(
            new FileOutputStream(new File(context.getFilesDir(), "support-correctness.json")),
            StandardCharsets.UTF_8)) {
      output.write(report.toString(2));
    }
  }
}
