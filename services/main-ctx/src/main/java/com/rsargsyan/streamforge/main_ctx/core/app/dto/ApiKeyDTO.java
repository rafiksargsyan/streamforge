package com.rsargsyan.streamforge.main_ctx.core.app.dto;

import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.ApiKey;

import java.time.Instant;

public record ApiKeyDTO(String id, String key, Instant createdAt, Instant lastUsedAt, String description, boolean enabled) {
  public static ApiKeyDTO from(ApiKey apiKey, String key) {
    return new ApiKeyDTO(apiKey.getStrId(), key, apiKey.getCreatedAt(), apiKey.getLastAccessTime(),
        apiKey.getDescription(), !apiKey.isDisabled());
  }

  public static ApiKeyDTO from(ApiKey apiKey) {
    return from(apiKey, null);
  }
}
