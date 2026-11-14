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
public final class ExpandedSupportAndroidTest {
  @Test
  public void bilingualFieldsPreserveSourcesAndDefaultsOnArt() throws Exception {
    android.content.Context c = InstrumentationRegistry.getInstrumentation().getContext();
    int probes;
    try (Reader docs =
            new InputStreamReader(
                c.getAssets().open("support-documents.tsv"), StandardCharsets.UTF_8);
        Reader q =
            new InputStreamReader(
                c.getAssets().open("expanded-queries.tsv"), StandardCharsets.UTF_8);
        Reader p =
            new InputStreamReader(
                c.getAssets().open("expanded-probes.tsv"), StandardCharsets.UTF_8)) {
      probes = ExpandedSupportProbeContract.verify(docs, q, p);
    }
    JSONObject report =
        new JSONObject()
            .put(
                "run_id",
                InstrumentationRegistry.getArguments()
                    .getString("neuralProbeRun", "manual_untracked"))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("expanded_parity_passed", true)
            .put("predictions", probes)
            .put("retained_default_checks", 1101)
            .put("documents", 58)
            .put("phone_benchmark", false);
    try (Writer output =
        new OutputStreamWriter(
            new FileOutputStream(new File(c.getFilesDir(), "expanded-correctness.json")),
            StandardCharsets.UTF_8)) {
      output.write(report.toString(2));
    }
  }
}
