package com.omnidev.workspace.domain.history;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class ChatMetadataModelTest {
  private static ChatMetadataModel.Result analyze(String... texts) {
    return ChatMetadataModel.analyze(Arrays.asList(texts), "app", "");
  }

  @Test
  public void titleUsesTheFirstSubstantiveUserRequest() {
    ChatMetadataModel.Result r =
        analyze("Hi Omni", "Please fix the Gradle build failure", "Also inspect the tests");
    assertEquals("fix the Gradle build failure", r.title);
    assertEquals("development", r.topic);
  }

  @Test
  public void arabicAndMixedRequestsAreCategorized() {
    assertEquals("development", analyze("ممكن تصلح كود Kotlin وGradle tests").topic);
    assertEquals("device", analyze("افحص بطارية جهاز اندرويد").topic);
    assertEquals("research", analyze("ابحث عن مصادر توثيق coroutines").topic);
  }

  @Test
  public void productHelpKeepsItsOwnTopic() {
    assertEquals("omni_help", analyze("ازاي افعل MCP في Omni").topic);
    assertTrue(ChatMetadataModel.hasService(analyze("ازاي افعل MCP في Omni").serviceTags, "mcp"));
  }

  @Test
  public void servicesAreMultiLabelButNeverChangeOrigin() {
    ChatMetadataModel.Result r = analyze("Compare WhatsApp Telegram and Discord connected apps");
    assertTrue(ChatMetadataModel.hasService(r.serviceTags, "whatsapp"));
    assertTrue(ChatMetadataModel.hasService(r.serviceTags, "telegram"));
    assertTrue(ChatMetadataModel.hasService(r.serviceTags, "discord"));
    assertTrue(ChatMetadataModel.hasService(r.serviceTags, "connected_apps"));
    assertEquals("messaging", r.topic);
  }

  @Test
  public void actualExternalOriginsContributeTags() {
    assertTrue(
        ChatMetadataModel.hasService(
            ChatMetadataModel.analyze(Arrays.asList("Hello"), "whatsapp_bridge", "").serviceTags,
            "whatsapp"));
    assertTrue(
        ChatMetadataModel.hasService(
            ChatMetadataModel.analyze(
                    Arrays.asList("Hello"), "external_app", "org.telegram.messenger")
                .serviceTags,
            "connected_apps"));
    assertTrue(
        ChatMetadataModel.hasService(
            ChatMetadataModel.analyze(
                    Arrays.asList("Hello"), "external_app", "org.telegram.messenger")
                .serviceTags,
            "telegram"));
  }

  @Test
  public void substringInsideAnotherWordDoesNotInventAService() {
    assertFalse(ChatMetadataModel.hasService(analyze("A legitimate result").serviceTags, "github"));
    assertFalse(ChatMetadataModel.hasService(analyze("omniconfig").serviceTags, "omnilink"));
  }

  @Test
  public void keysAndBearerValuesCannotLeakIntoAutomaticTitles() {
    String secret = "sk-abcdefghijk123456789";
    ChatMetadataModel.Result r = analyze("Configure Telegram API key: " + secret);
    assertFalse(r.title.contains(secret));
    assertTrue(ChatMetadataModel.hasService(r.serviceTags, "telegram"));
    assertFalse(
        analyze("Authorization: Bearer abcdefghijklmnopqrstuv").title.contains("abcdefghijkl"));
  }

  @Test
  public void contactIdentifiersAndUrlsAreRemoved() {
    String t =
        analyze("Help with account jane@example.com https://private.example/a?token=secret").title;
    assertFalse(t.contains("jane@"));
    assertFalse(t.contains("https://"));
    assertFalse(t.contains("secret"));
  }

  @Test
  public void codeBlocksDoNotBecomeTheTitle() {
    assertEquals("Fix the error", analyze("Fix the error\n```kotlin\nval secret = 123\n```").title);
    assertFalse(analyze("```java\nprivateKey=123\n```").title.contains("privateKey"));
  }

  @Test
  public void runtimeStatusCannotBeSpoofedByAGeneratedTitle() {
    assertFalse(analyze("Fix Gradle • Status: Completed").title.contains("Status:"));
  }

  @Test
  public void titleAndInputWorkAreBoundedAndUnicodeSafe() {
    String text = String.join("", Collections.nCopies(4000, "🙂"));
    String t = analyze(text).title;
    assertTrue(t.codePointCount(0, t.length()) <= 64);
    assertFalse(t.contains("�"));
    List<String> many = new ArrayList<>(Collections.nCopies(6, "Hello"));
    many.add("WhatsApp Telegram");
    assertFalse(
        ChatMetadataModel.hasService(
            ChatMetadataModel.analyze(many, "app", "").serviceTags, "whatsapp"));
  }

  @Test
  public void emptyAndNonChatInputsRemainHarmless() {
    assertEquals("New conversation", analyze("").title);
    assertEquals("general", analyze("").topic);
    assertEquals("New conversation", ChatMetadataModel.analyze(null, "app", "").title);
  }

  @Test
  public void searchCombinesSavedTopicServicesAndArabicNormalization() {
    ChatMetadataModel.Result r = analyze("Set up WhatsApp");
    assertTrue(ChatMetadataModel.matches("واتساب", "Setup", "App", r.topic, r.serviceTags));
    assertTrue(ChatMetadataModel.matches("إعداد", "إعداد حساب", "App", r.topic, r.serviceTags));
    assertFalse(ChatMetadataModel.matches("discord", "Setup", "App", r.topic, r.serviceTags));
  }
}
