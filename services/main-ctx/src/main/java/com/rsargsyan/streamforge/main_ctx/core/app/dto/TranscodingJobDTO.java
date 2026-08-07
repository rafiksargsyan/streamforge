package com.rsargsyan.streamforge.main_ctx.core.app.dto;

import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.TranscodingJob;
import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.TranscodeSpec;
import lombok.Value;
import org.springframework.lang.Nullable;

import java.net.URL;
import java.time.Instant;
import java.util.List;

@Value
public class TranscodingJobDTO {
  String id;
  URL videoUrl;
  String status;
  TranscodeSpec spec;
  Instant createdAt;
  Instant startedAt;
  Instant finishedAt;
  @Nullable String downloadUrl;
  @Nullable Instant expiresAt;
  @Nullable JobFailureReason failureReason;
  // Positions within spec.texts() that Shaka Packager rejected as invalid WebVTT and were
  // dropped from packaging - consumers should treat these text tracks as not existing.
  List<Integer> failedSubtitleIndexes;

  public static TranscodingJobDTO from(TranscodingJob job, String downloadUrl, @Nullable Instant expiresAt) {
    JobFailureReason failureReason = job.getFailureReason() != null
        ? JobFailureReason.from(job.getFailureReason())
        : null;
    return new TranscodingJobDTO(
        job.getStrId(), job.getVideoURL(), externalStatus(job), job.getSpec(),
        job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt(),
        downloadUrl, expiresAt, failureReason, job.getFailedSubtitleIndexes()
    );
  }

  private static String externalStatus(TranscodingJob job) {
    return switch (job.getStatus()) {
      case QUEUED, RECEIVED -> job.getRetryCount() > 0 ? "IN_PROGRESS" : "SUBMITTED";
      case RETRYING -> "IN_PROGRESS";
      default -> job.getStatus().name();
    };
  }
}
