package com.rsargsyan.streamforge.main_ctx.core.exception;

import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.TranscodingJob;

public class IllegalJobStateTransitionException extends DomainException {
  public IllegalJobStateTransitionException(TranscodingJob.Status from, TranscodingJob.Status to) {
    super("Illegal job state transition: %s → %s".formatted(from, to));
  }
}
