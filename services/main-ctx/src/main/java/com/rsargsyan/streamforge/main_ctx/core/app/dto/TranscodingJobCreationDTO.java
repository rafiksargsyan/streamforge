package com.rsargsyan.streamforge.main_ctx.core.app.dto;

import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.TranscodeSpec;
import lombok.Value;

@Value
public class TranscodingJobCreationDTO {
  String videoURL;
  TranscodeSpec spec;
}
