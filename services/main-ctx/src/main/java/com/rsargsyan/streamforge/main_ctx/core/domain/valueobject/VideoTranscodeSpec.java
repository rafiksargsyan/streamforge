package com.rsargsyan.streamforge.main_ctx.core.domain.valueobject;

import com.rsargsyan.streamforge.main_ctx.core.exception.InvalidTranscodeSpecException;

import java.util.List;

public record VideoTranscodeSpec(int stream, List<VideoRendition> renditions) {
  public VideoTranscodeSpec {
    if (stream < 0) throw new InvalidTranscodeSpecException("Video stream index must be non-negative");
    if (renditions == null || renditions.isEmpty())
      throw new InvalidTranscodeSpecException("At least one video rendition is required");
    renditions = List.copyOf(renditions);
  }
}
