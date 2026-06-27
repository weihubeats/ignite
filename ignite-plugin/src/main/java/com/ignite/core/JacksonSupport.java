package com.ignite.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JacksonSupport {

  /** OGNL 在目标 JVM 中使用的 ObjectMapper，需注册 JavaTimeModule 以支持 java.time.* */
  static final String OGNL_MAPPER =
      "(new com.fasterxml.jackson.databind.ObjectMapper()).registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())";

  private static final ObjectMapper MAPPER = createMapper();

  private JacksonSupport() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static ObjectMapper createMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
