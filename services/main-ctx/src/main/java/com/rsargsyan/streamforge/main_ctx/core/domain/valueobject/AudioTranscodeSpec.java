package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

public record AudioTranscodeSpec(int stream, int bitrateKbps, int channels, Lang lang, String name, String fileName) {
  public AudioTranscodeSpec {
    if (stream < 0) throw new InvalidTranscodeSpecException("Audio stream index must be non-negative");
    if (bitrateKbps <= 0) throw new InvalidTranscodeSpecException("Audio bitrate must be positive");
    if (channels <= 0) throw new InvalidTranscodeSpecException("Audio channels must be positive");
    if (lang == null) throw new InvalidTranscodeSpecException("Audio language is required");
    if (name == null || name.isBlank()) throw new InvalidTranscodeSpecException("Audio track name is required");
    if (fileName == null || fileName.isBlank()) throw new InvalidTranscodeSpecException("Audio file name is required");
  }
}
