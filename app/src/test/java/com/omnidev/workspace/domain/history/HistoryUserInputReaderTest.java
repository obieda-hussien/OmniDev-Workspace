package com.omnidev.workspace.domain.history;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.util.Collections;
import org.junit.Test;

public final class HistoryUserInputReaderTest {
  private static String read(String s) {
    return HistoryUserInputReader.readPrefix(new StringReader(s));
  }

  private static String repeat(String s, int n) {
    return String.join("", Collections.nCopies(n, s));
  }

  @Test
  public void longValueDoesNotRequireTheRestOfTheEnvelope() {
    assertEquals(
        1500, read("{\"userInput\":\"" + repeat("a", 100000) + "\",\"tail\":{}}").length());
  }

  @Test
  public void lateRootFieldSurvivesNestedObjectsArraysAndUnicode() {
    assertEquals(
        "Fix Gradle",
        read(
            "{\"context\":[{\"userInput\":\"wrong\",\"large\":\""
                + repeat("🙂", 6000)
                + "\"},null,true,42,-3.2e4],\"userInput\":\"Fix Gradle\"}"));
  }

  @Test
  public void escapedStringsAndNamesAreDecoded() {
    assertEquals(
        "Hi\n\"Omni\" 🙂", read("{\"\\u0075serInput\":\"Hi\\n\\\"Omni\\\" \\ud83d\\ude42\"}"));
  }

  @Test
  public void prefixNeverSplitsASurrogatePair() {
    assertEquals(1499, read("{\"userInput\":\"" + repeat("a", 1499) + "🙂tail\"}").length());
  }

  @Test
  public void malformedOrAbsentInputReturnsNull() {
    for (String s :
        new String[] {
          "",
          "{broken",
          "{}",
          "{\"nested\":{\"userInput\":\"foreign\"}}",
          "{\"userInput\":null}",
          "{\"userInput\":\"bad\\z\"}"
        }) assertNull(read(s));
  }

  @Test
  public void scanningStopsAtTheExplicitBound() {
    assertThrows(
        HistoryUserInputReader.ScanLimitExceeded.class,
        () ->
            read(
                "{\"context\":\""
                    + repeat("x", HistoryUserInputReader.MAX_SCAN)
                    + "\",\"userInput\":\"not reached\"}"));
  }
}
