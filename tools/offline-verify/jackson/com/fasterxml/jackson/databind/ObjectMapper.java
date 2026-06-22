package com.fasterxml.jackson.databind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful-enough ObjectMapper for offline verification: a real recursive-descent JSON parser + serializer
 * covering the surface the codebase uses (readTree, readValue(Map/List/Object), writeValueAsString,
 * valueToTree, convertValue). Offline test scaffold ONLY; production uses real Jackson via Spring Boot.
 */
public class ObjectMapper {

  // ---- public API ----
  public JsonNode readTree(String s) { return JsonNode.of(new Parser(s).parseValue()); }
  public JsonNode readTree(byte[] b) { return readTree(new String(b, StandardCharsets.UTF_8)); }

  @SuppressWarnings("unchecked")
  public <T> T readValue(String s, Class<T> c) { return (T) new Parser(s).parseValue(); }
  public <T> T readValue(byte[] b, Class<T> c) { return readValue(new String(b, StandardCharsets.UTF_8), c); }

  public String writeValueAsString(Object o) { return serialize(unwrap(o)); }
  public byte[] writeValueAsBytes(Object o) { return writeValueAsString(o).getBytes(StandardCharsets.UTF_8); }
  public ObjectMapper writerWithDefaultPrettyPrinter() { return this; }
  public JsonNode valueToTree(Object o) { return JsonNode.of(unwrap(o)); }
  @SuppressWarnings("unchecked")
  public <T> T convertValue(Object o, Class<T> c) { return (T) unwrap(o); }
  public ObjectMapper configure(Object f, boolean state) { return this; }

  private static Object unwrap(Object o) { return o instanceof JsonNode ? ((JsonNode) o).value : o; }

  // ---- serializer ----
  static String serialize(Object v) {
    StringBuilder sb = new StringBuilder();
    write(sb, v);
    return sb.toString();
  }
  @SuppressWarnings("unchecked")
  private static void write(StringBuilder sb, Object v) {
    if (v == null) { sb.append("null"); return; }
    if (v instanceof JsonNode) { write(sb, ((JsonNode) v).value); return; }
    if (v instanceof String) { writeString(sb, (String) v); return; }
    if (v instanceof Boolean || v instanceof Number) { sb.append(v.toString()); return; }
    if (v instanceof Map) {
      sb.append('{'); boolean first = true;
      for (Map.Entry<?,?> e : ((Map<?,?>) v).entrySet()) {
        if (!first) sb.append(','); first = false;
        writeString(sb, String.valueOf(e.getKey())); sb.append(':'); write(sb, e.getValue());
      }
      sb.append('}'); return;
    }
    if (v instanceof Iterable) {
      sb.append('['); boolean first = true;
      for (Object o : (Iterable<Object>) v) { if (!first) sb.append(','); first = false; write(sb, o); }
      sb.append(']'); return;
    }
    if (v instanceof Object[]) {
      sb.append('['); boolean first = true;
      for (Object o : (Object[]) v) { if (!first) sb.append(','); first = false; write(sb, o); }
      sb.append(']'); return;
    }
    writeString(sb, String.valueOf(v));
  }
  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
      }
    }
    sb.append('"');
  }

  // ---- parser (recursive descent) ----
  private static final class Parser {
    private final String s; private int i;
    Parser(String s) { this.s = s == null ? "" : s; this.i = 0; }
    Object parseValue() {
      ws();
      if (i >= s.length()) return null;
      char c = s.charAt(i);
      switch (c) {
        case '{': return parseObject();
        case '[': return parseArray();
        case '"': return parseString();
        case 't': case 'f': return parseBool();
        case 'n': i += 4; return null; // null
        default: return parseNumber();
      }
    }
    private Map<String,Object> parseObject() {
      Map<String,Object> m = new LinkedHashMap<>(); i++; ws();
      if (peek() == '}') { i++; return m; }
      while (true) {
        ws(); String k = parseString(); ws(); expect(':');
        Object v = parseValue(); m.put(k, v); ws();
        char c = next();
        if (c == ',') continue; if (c == '}') break;
        throw new RuntimeException("bad object at " + i);
      }
      return m;
    }
    private List<Object> parseArray() {
      List<Object> l = new ArrayList<>(); i++; ws();
      if (peek() == ']') { i++; return l; }
      while (true) {
        Object v = parseValue(); l.add(v); ws();
        char c = next();
        if (c == ',') continue; if (c == ']') break;
        throw new RuntimeException("bad array at " + i);
      }
      return l;
    }
    private String parseString() {
      ws(); expect('"'); StringBuilder sb = new StringBuilder();
      while (true) {
        char c = s.charAt(i++);
        if (c == '"') break;
        if (c == '\\') {
          char e = s.charAt(i++);
          switch (e) {
            case '"': sb.append('"'); break; case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break; case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break; case 't': sb.append('\t'); break;
            case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break;
            case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
            default: sb.append(e);
          }
        } else sb.append(c);
      }
      return sb.toString();
    }
    private Object parseBool() {
      if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
      i += 5; return Boolean.FALSE;
    }
    private Object parseNumber() {
      int start = i;
      while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
      String num = s.substring(start, i);
      if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
      try { return Long.parseLong(num); } catch (NumberFormatException e) { return Double.parseDouble(num); }
    }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { ws(); return i < s.length() ? s.charAt(i) : '\0'; }
    private char next() { ws(); return s.charAt(i++); }
    private void expect(char c) { ws(); if (s.charAt(i++) != c) throw new RuntimeException("expected " + c + " at " + (i-1)); }
  }
}
