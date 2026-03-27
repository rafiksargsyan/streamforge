package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

public record VideoRendition(int resolution, String fileName) {
  public VideoRendition {
    if (resolution <= 0) throw new InvalidTranscodeSpecException("Video rendition resolution must be positive");
    if (resolution > 1080) throw new InvalidTranscodeSpecException("Unsupported video resolution: " + resolution + " (max 1080)");
    if (fileName == null || fileName.isBlank()) throw new InvalidTranscodeSpecException("Video rendition file name is required");
  }
}
