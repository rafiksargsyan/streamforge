package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

public record TextTranscodeSpec(Lang lang, String name, String fileName, int stream) {
  public TextTranscodeSpec {
    if (lang == null) throw new InvalidTranscodeSpecException("Subtitle language is required");
    if (name == null || name.isBlank()) throw new InvalidTranscodeSpecException("Subtitle track name is required");
    if (fileName == null || fileName.isBlank()) throw new InvalidTranscodeSpecException("Subtitle file name is required");
    if (stream < 0) throw new InvalidTranscodeSpecException("Subtitle stream index must be non-negative");
  }
}
