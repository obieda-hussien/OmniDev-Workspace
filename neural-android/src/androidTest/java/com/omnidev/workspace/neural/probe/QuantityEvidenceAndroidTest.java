package com.omnidev.workspace.neural.probe;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class QuantityEvidenceAndroidTest {
  @Test
  public void matchesHostDecisionsAndVerbatimWitnesses() throws Exception {
    android.content.res.AssetManager assets =
        InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    try (Reader docs =
            new InputStreamReader(assets.open("support-documents.tsv"), StandardCharsets.UTF_8);
        Reader cases =
            new InputStreamReader(assets.open("quantity-evidence.tsv"), StandardCharsets.UTF_8)) {
      int count = QuantityEvidenceProbeContract.verify(docs, cases);
      org.json.JSONObject report =
          new org.json.JSONObject()
              .put("cases", count)
              .put("sdk", android.os.Build.VERSION.SDK_INT)
              .put(
                  "run_id",
                  InstrumentationRegistry.getArguments().getString("neuralProbeRun", "manual"));
      File target =
          new File(
              InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
              "quantity-evidence.json");
      try (Writer writer =
          new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
        writer.write(report.toString());
      }
    }
  }
}
