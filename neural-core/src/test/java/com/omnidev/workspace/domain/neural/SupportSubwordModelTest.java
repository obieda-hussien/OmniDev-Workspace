package com.omnidev.workspace.domain.neural;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class SupportSubwordModelTest {
  static SupportSubwordModel make(
      SupportQueryMemory memory, TrainTokenCoverage tokens, boolean active) {
    return new SupportSubwordModel(
        memory,
        tokens,
        SupportReliabilityModelTest.head(2),
        1,
        .5,
        .5,
        0,
        active,
        SupportSubwordModel.binding(memory, tokens),
        SupportSubwordModel.VERSION);
  }

  @Test
  public void tolerantCoverageChangesOnlyExperimentalAcceptance() {
    SupportQueryMemory memory =
        SupportQueryMemoryTest.make(SupportQueryMemoryTest.examples(), null);
    TrainTokenCoverage tokens = new TrainTokenCoverage(SupportQueryMemoryTest.examples());
    SupportSubwordModel model = make(memory, tokens, true);
    SupportSubwordModel.Result r = model.search("microphne", 10);
    assertTrue(r.softCoverage >= .6);
    assertTrue(r.learnedAccepted);
    SupportQueryMemory.Result old = memory.search("microphne", 10);
    assertEquals(old.matches.size(), r.matches.size());
    for (int i = 0; i < old.matches.size(); i++) {
      assertSame(old.matches.get(i).topic, r.matches.get(i).topic);
      assertEquals(old.matches.get(i).score, r.matches.get(i).score, 0);
    }
    assertEquals(old.accepted, make(memory, tokens, false).search("microphne", 10).accepted);
    assertFalse(model.search("", 1).accepted);
    assertEquals(model.search("microphne", 1).accepted, r.accepted);
  }

  @Test
  public void checkpointRejectsForeignLexiconDespiteUnchangedRankingCorpus() {
    SupportQueryMemory memory =
        SupportQueryMemoryTest.make(SupportQueryMemoryTest.examples(), null);
    TrainTokenCoverage tokens = new TrainTokenCoverage(SupportQueryMemoryTest.examples());
    List<SupportQueryMemory.Exemplar> edited = new ArrayList<>(SupportQueryMemoryTest.examples());
    edited.set(
        0,
        new SupportQueryMemory.Exemplar(
            "voice-en", "scenario-one", "voice", "different token words"));
    TrainTokenCoverage foreign = new TrainTokenCoverage(edited);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportSubwordModel(
                memory,
                foreign,
                SupportReliabilityModelTest.head(2),
                1,
                .5,
                0,
                0,
                true,
                SupportSubwordModel.binding(memory, tokens),
                SupportSubwordModel.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportSubwordModel(
                memory,
                tokens,
                SupportReliabilityModelTest.head(2),
                Double.NaN,
                .5,
                0,
                0,
                true,
                SupportSubwordModel.binding(memory, tokens),
                SupportSubwordModel.VERSION));
    assertNotEquals(
        make(memory, tokens, true).fingerprint(), make(memory, tokens, false).fingerprint());
  }
}
