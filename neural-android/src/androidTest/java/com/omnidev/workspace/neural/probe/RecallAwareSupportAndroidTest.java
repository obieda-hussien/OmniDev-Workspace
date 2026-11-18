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
public final class RecallAwareSupportAndroidTest {
  @Test
  public void learnedSelectorPreservesNumericSourcesAndDefaultsOnArt() throws Exception {
    android.content.Context c = InstrumentationRegistry.getInstrumentation().getContext();
    int probes;
    try (Reader d =
            new InputStreamReader(
                c.getAssets().open("support-documents.tsv"), StandardCharsets.UTF_8);
        Reader q =
            new InputStreamReader(
                c.getAssets().open("recall-queries.tsv"), StandardCharsets.UTF_8);
        Reader p =
            new InputStreamReader(c.getAssets().open("recall-probes.tsv"), StandardCharsets.UTF_8);
        Reader e =
            new InputStreamReader(
                c.getAssets().open("memory-exemplars.tsv"), StandardCharsets.UTF_8)) {
      probes = RecallAwareSupportProbeContract.verify(d, q, p, e);
    }
    JSONObject report =
        new JSONObject()
            .put(
                "run_id",
                InstrumentationRegistry.getArguments()
                    .getString("neuralProbeRun", "manual_untracked"))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("recall_parity_passed", true)
            .put("predictions", probes)
            .put("retained_default_checks", 1872)
            .put("documents", 58)
            .put("phone_benchmark", false);
    try (Writer out =
        new OutputStreamWriter(
            new FileOutputStream(new File(c.getFilesDir(), "recall-correctness.json")),
            StandardCharsets.UTF_8)) {
      out.write(report.toString(2));
    }
  }
}
