package com.fasterxml.jackson.databind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful-enough JsonNode for offline verification. Wraps a parsed JSON value: Map / List / String /
 * Long / Double / Boolean / null. A dedicated MISSING node models Jackson's missing-node semantics.
 * NOTE: this lives in the offline test scaffold only; production uses real Jackson (via Spring Boot).
 */
public class JsonNode implements Iterable<JsonNode> {
  static final JsonNode MISSING = new JsonNode(null, true);
  final Object value;
  private final boolean missing;

  public JsonNode() { this(null, false); }
  JsonNode(Object value) { this(value, false); }
  private JsonNode(Object value, boolean missing) { this.value = value; this.missing = missing; }

  static JsonNode of(Object v) { return v == null ? new JsonNode(null, false) : new JsonNode(v, false); }

  @SuppressWarnings("unchecked")
  public JsonNode get(String f) {
    if (value instanceof Map) {
      Map<String,Object> m = (Map<String,Object>) value;
      return m.containsKey(f) ? of(m.get(f)) : null; // Jackson: get() returns null when absent
    }
    return null;
  }
  @SuppressWarnings("unchecked")
  public JsonNode get(int i) {
    if (value instanceof List) {
      List<Object> l = (List<Object>) value;
      return (i >= 0 && i < l.size()) ? of(l.get(i)) : null;
    }
    return null;
  }
  @SuppressWarnings("unchecked")
  public JsonNode path(String f) {
    if (value instanceof Map) {
      Map<String,Object> m = (Map<String,Object>) value;
      return m.containsKey(f) ? of(m.get(f)) : MISSING;
    }
    return MISSING;
  }

  public String asText() {
    if (value == null) return "";
    if (value instanceof String) return (String) value;
    if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
    return "";
  }
  public String asText(String d) { return isValueNode() && value != null ? asText() : d; }
  public int asInt() { return asInt(0); }
  public int asInt(int d) {
    if (value instanceof Number) return ((Number) value).intValue();
    if (value instanceof String) { try { return Integer.parseInt(((String) value).trim()); } catch (Exception e) { return d; } }
    return d;
  }
  public long asLong() { return asLong(0L); }
  public long asLong(long d) {
    if (value instanceof Number) return ((Number) value).longValue();
    if (value instanceof String) { try { return Long.parseLong(((String) value).trim()); } catch (Exception e) { return d; } }
    return d;
  }
  public boolean asBoolean() {
    if (value instanceof Boolean) return (Boolean) value;
    if (value instanceof String) return Boolean.parseBoolean((String) value);
    return false;
  }

  public boolean has(String f) { return value instanceof Map && ((Map<?,?>) value).containsKey(f); }
  public boolean isArray() { return value instanceof List; }
  public boolean isObject() { return value instanceof Map; }
  public boolean isMissingNode() { return missing; }
  public boolean isNull() { return !missing && value == null; }
  public boolean isValueNode() { return !missing && !(value instanceof Map) && !(value instanceof List); }
  public int size() {
    if (value instanceof List) return ((List<?>) value).size();
    if (value instanceof Map) return ((Map<?,?>) value).size();
    return 0;
  }

  @SuppressWarnings("unchecked")
  public Iterator<JsonNode> elements() {
    if (value instanceof List) {
      List<JsonNode> out = new ArrayList<>();
      for (Object o : (List<Object>) value) out.add(of(o));
      return out.iterator();
    }
    return Collections.emptyIterator();
  }
  @SuppressWarnings("unchecked")
  public Iterator<String> fieldNames() {
    if (value instanceof Map) return new ArrayList<>(((Map<String,Object>) value).keySet()).iterator();
    return Collections.emptyIterator();
  }
  @SuppressWarnings("unchecked")
  public Iterator<Map.Entry<String,JsonNode>> fields() {
    if (value instanceof Map) {
      Map<String,JsonNode> out = new LinkedHashMap<>();
      for (Map.Entry<String,Object> e : ((Map<String,Object>) value).entrySet()) out.put(e.getKey(), of(e.getValue()));
      return out.entrySet().iterator();
    }
    return Collections.emptyIterator();
  }
  @Override public Iterator<JsonNode> iterator() { return elements(); }

  @Override public String toString() { return ObjectMapper.serialize(value); }
}
