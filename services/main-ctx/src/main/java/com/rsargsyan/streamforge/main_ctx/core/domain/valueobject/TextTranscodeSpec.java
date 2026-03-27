package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

public record TextTranscodeSpec(Lang lang, String name, String fileName, String src) {
  public TextTranscodeSpec {
    if (lang == null) throw new InvalidTranscodeSpecException("Subtitle language is required");
    if (name == null || name.isBlank()) throw new InvalidTranscodeSpecException("Subtitle track name is required");
    if (fileName == null || fileName.isBlank()) throw new InvalidTranscodeSpecException("Subtitle file name is required");
    if (src == null || src.isBlank()) throw new InvalidTranscodeSpecException("Subtitle source URL is required");
  }
}
