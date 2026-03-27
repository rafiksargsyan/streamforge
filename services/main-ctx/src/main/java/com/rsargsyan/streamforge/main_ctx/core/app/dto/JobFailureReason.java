package com.rsargsyan.streamforge.main_ctx.core.app.dto;

import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.FailureReason;

public enum JobFailureReason {
  VIDEO_TOO_LARGE,
  VIDEO_NOT_ACCESSIBLE,
  SERVER_ERROR;

  public static JobFailureReason from(FailureReason reason) {
    return switch (reason) {
      case VIDEO_TOO_LARGE -> VIDEO_TOO_LARGE;
      case VIDEO_NOT_ACCESSIBLE -> VIDEO_NOT_ACCESSIBLE;
      default -> SERVER_ERROR;
    };
  }
}
