package com.rsargsyan.streamforge.main_ctx.adapters.driving.controllers;

import com.rsargsyan.streamforge.main_ctx.core.app.TranscodingJobService;
import com.rsargsyan.streamforge.main_ctx.core.app.dto.TranscodingJobCreationDTO;
import com.rsargsyan.streamforge.main_ctx.core.app.dto.TranscodingJobDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Profile("web")
@RestController
@RequestMapping("/transcoding-job")
public class TranscodingJobController {

  private final TranscodingJobService transcodingJobService;

  @Autowired
  public TranscodingJobController(TranscodingJobService transcodingJobService) {
    this.transcodingJobService = transcodingJobService;
  }

  @GetMapping
  public ResponseEntity<Page<TranscodingJobDTO>> findAll(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    var userCtx = UserContextHolder.get();
    return ResponseEntity.ok(transcodingJobService.findAll(userCtx.getAccountId(), page, size));
  }

  @GetMapping("/{id}")
  public ResponseEntity<TranscodingJobDTO> findById(@PathVariable String id) {
    var userCtx = UserContextHolder.get();
    return ResponseEntity.ok(transcodingJobService.findById(userCtx.getAccountId(), id));
  }

  @GetMapping("/limits")
  public ResponseEntity<Map<String, Long>> getLimits() {
    return ResponseEntity.ok(Map.of("maxFileSizeBytes", transcodingJobService.getMaxFileSizeBytes()));
  }

  @PostMapping
  public ResponseEntity<TranscodingJobDTO> createJob(@RequestBody TranscodingJobCreationDTO req) {
    var userCtx = UserContextHolder.get();
    TranscodingJobDTO job = transcodingJobService.create(userCtx.getAccountId(), req);
    return new ResponseEntity<>(job, HttpStatus.CREATED);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> cancel(@PathVariable String id) {
    var userCtx = UserContextHolder.get();
    transcodingJobService.cancel(userCtx.getAccountId(), id);
    return ResponseEntity.noContent().build();
  }
}
