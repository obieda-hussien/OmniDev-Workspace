package com.omnidev.workspace.domain.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Bounded root-field extraction from paged legacy metadata; never materializes a full envelope. */
public final class HistoryUserInputReader {
  public static final int MAX_SCAN = 262144, MAX_PREFIX = 1500;
  private final Reader reader;
  private int pending = -2, scanned;

  private HistoryUserInputReader(Reader reader) {
    this.reader = new BufferedReader(reader, 2048);
  }

  public static String readPrefix(Reader reader) {
    if (reader == null) throw new IllegalArgumentException("Missing metadata reader");
    try {
      return new HistoryUserInputReader(reader).root();
    } catch (IOException | IllegalArgumentException invalid) {
      return null;
    }
  }

  private String root() throws IOException {
    require(next() == '{');
    if (peek() == '}') return null;
    while (true) {
      require(next() == '"');
      String key = string(64, false);
      require(next() == ':');
      if (key.equals("userInput")) {
        if (next() != '"') return null;
        String value = string(MAX_PREFIX, true);
        return value.length() > 0 && Character.isHighSurrogate(value.charAt(value.length() - 1))
            ? value.substring(0, value.length() - 1)
            : value;
      }
      skip(0);
      int separator = next();
      if (separator == '}') return null;
      require(separator == ',');
    }
  }

  private void skip(int depth) throws IOException {
    require(depth < 64);
    int c = next();
    if (c == '"') {
      string(0, false);
      return;
    }
    if (c == '{' || c == '[') {
      int end = c == '{' ? '}' : ']';
      if (peek() == end) {
        next();
        return;
      }
      while (true) {
        if (c == '{') {
          require(next() == '"');
          string(0, false);
          require(next() == ':');
        }
        skip(depth + 1);
        int separator = next();
        if (separator == end) return;
        require(separator == ',');
      }
    }
    StringBuilder token = new StringBuilder();
    token.append((char) c);
    while (true) {
      int p = read();
      if (p < 0 || p == ',' || p == '}' || p == ']' || Character.isWhitespace(p)) {
        pending = p;
        break;
      }
      require(token.length() < 128);
      token.append((char) p);
    }
    require(
        token
            .toString()
            .matches("(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"));
  }

  private String string(int limit, boolean early) throws IOException {
    StringBuilder out = new StringBuilder(Math.min(limit, MAX_PREFIX));
    while (true) {
      int c = read();
      require(c >= 32);
      if (c == '"') return out.toString();
      if (c == '\\') {
        c = read();
        switch (c) {
          case '"':
          case '\\':
          case '/':
            break;
          case 'b':
            c = '\b';
            break;
          case 'f':
            c = '\f';
            break;
          case 'n':
            c = '\n';
            break;
          case 'r':
            c = '\r';
            break;
          case 't':
            c = '\t';
            break;
          case 'u':
            c = 0;
            for (int i = 0; i < 4; i++) {
              int d = Character.digit(read(), 16);
              require(d >= 0);
              c = c * 16 + d;
            }
            break;
          default:
            throw new IllegalArgumentException("Invalid JSON escape");
        }
      }
      if (out.length() < limit) out.append((char) c);
      if (early && out.length() == limit) return out.toString();
    }
  }

  private int peek() throws IOException {
    int c = next();
    pending = c;
    return c;
  }

  private int next() throws IOException {
    int c;
    do {
      c = read();
    } while (c >= 0 && Character.isWhitespace(c));
    require(c >= 0);
    return c;
  }

  private int read() throws IOException {
    if (pending != -2) {
      int c = pending;
      pending = -2;
      return c;
    }
    if (++scanned > MAX_SCAN) throw new IOException("Metadata scan bound");
    return reader.read();
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("Invalid metadata JSON");
  }
}
