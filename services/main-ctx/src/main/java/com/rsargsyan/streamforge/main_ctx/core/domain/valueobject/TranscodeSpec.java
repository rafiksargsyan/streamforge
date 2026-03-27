package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

import java.util.List;

public record TranscodeSpec(VideoTranscodeSpec video, List<AudioTranscodeSpec> audios, List<TextTranscodeSpec> texts) {
  public TranscodeSpec {
    if (video == null) throw new InvalidTranscodeSpecException("Video transcode spec is required");
    if (audios == null || audios.isEmpty())
      throw new InvalidTranscodeSpecException("At least one audio track is required");
    audios = List.copyOf(audios);
    texts = texts != null ? List.copyOf(texts) : List.of();
  }
}
