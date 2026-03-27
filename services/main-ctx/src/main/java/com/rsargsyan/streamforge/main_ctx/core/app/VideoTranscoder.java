package com.rsargsyan.streamforge.main_ctx.core.app;

import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.AudioTranscodeSpec;
import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.TextTranscodeSpec;
import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.TranscodeSpec;
import com.rsargsyan.streamforge.main_ctx.core.domain.valueobject.VideoRendition;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class VideoTranscoder {

  public static void transcode(String inputPath, Path outputFolder, TranscodeSpec spec,
                               int ffmpegThreads, Consumer<Process> onProcess) throws Exception {
    Path workDir = outputFolder.resolve("work");
    Files.createDirectories(workDir);
    Path vodDir = outputFolder.resolve("vod");
    Files.createDirectories(vodDir);
    Path subtitlesDir = outputFolder.resolve("subtitles");
    Files.createDirectories(subtitlesDir);

    boolean hdr = isHdr(inputPath);
    List<String> shakaInputs = new ArrayList<>();

    // 1. Transcode video renditions (H.264, profile-based)
    for (VideoRendition rendition : spec.video().renditions()) {
      Path renditionPath = workDir.resolve(rendition.fileName());
      transcodeVideo(inputPath, spec.video().stream(), rendition, hdr, renditionPath, ffmpegThreads, onProcess);
      shakaInputs.add("in=%s,stream=video,output=%s".formatted(renditionPath, rendition.fileName()));
    }

    // 2. Transcode audio tracks (AAC)
    for (AudioTranscodeSpec audioSpec : spec.audios()) {
      Path audioPath = workDir.resolve(audioSpec.fileName());
      transcodeAudio(inputPath, audioSpec, audioPath, onProcess);
      shakaInputs.add("in=%s,stream=audio,output=%s,lang=%s,hls_group_id=audio,hls_name='%s',dash_label='%s'".formatted(
          audioPath, audioSpec.fileName(),
          audioSpec.lang().name().toLowerCase(),
          audioSpec.name(), audioSpec.name()));
    }

    // 3. Process subtitle tracks (WebVTT)
    for (TextTranscodeSpec textSpec : spec.texts()) {
      Path subtitleWorkPath = workDir.resolve(textSpec.fileName());
      transcodeSubtitle(textSpec, subtitleWorkPath, onProcess);
      appendDummyCue(subtitleWorkPath);
      Files.copy(subtitleWorkPath, subtitlesDir.resolve(textSpec.fileName()),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      shakaInputs.add("in=%s,stream=text,output=%s,lang=%s,hls_group_id=subtitle,hls_name='%s',dash_label='%s'".formatted(
          subtitleWorkPath, textSpec.fileName(),
          textSpec.lang().name().toLowerCase(),
          textSpec.name(), textSpec.name()));
    }

    // 4. Package with Shaka Packager (CWD = vod/, so segment files land there)
    String mpdOutput = vodDir.resolve("manifest.mpd").toString();
    String hlsOutput = vodDir.resolve("master.m3u8").toString();

    List<String> shakaCmd = new ArrayList<>();
    shakaCmd.add("packager");
    shakaCmd.addAll(shakaInputs);
    shakaCmd.addAll(List.of(
        "--mpd_output", mpdOutput,
        "--hls_master_playlist_output", hlsOutput,
        "--segment_duration", "6"
    ));
    runProcess(shakaCmd, vodDir, onProcess);

    // 5. Strip shaka-packager comment lines injected by the packager
    stripShakaComments(vodDir, "*.mpd");
    stripShakaComments(vodDir, "*.m3u8");
    if (!spec.texts().isEmpty()) {
      stripShakaComments(vodDir, "*.vtt");
    }
  }

  private static void transcodeVideo(String inputPath, int stream, VideoRendition rendition,
                                     boolean hdr, Path outputPath, int threads,
                                     Consumer<Process> onProcess) throws Exception {
    int resolution = rendition.resolution();
    String level, profile, maxRate, bufSize;
    int crf;
    if (resolution <= 360) {
      level = "3.0"; profile = "baseline"; crf = 18; maxRate = "600k"; bufSize = "1200k";
    } else if (resolution <= 480) {
      level = "3.1"; profile = "main"; crf = 18; maxRate = "1200k"; bufSize = "2400k";
    } else if (resolution <= 720) {
      level = "4.0"; profile = "main"; crf = 18; maxRate = "3000k"; bufSize = "6000k";
    } else {
      level = "4.2"; profile = "high"; crf = 19; maxRate = "5000k"; bufSize = "10000k";
    }

    String vf = "scale=-2:%d,format=yuv420p".formatted(resolution);
    if (hdr) {
      vf = "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709," +
           "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv," + vf;
    }

    List<String> cmd = List.of(
        "ffmpeg", "-y",
        "-i", inputPath,
        "-an", "-sn",
        "-c:v:" + stream, "libx264",
        "-profile:v", profile,
        "-level:v", level,
        "-x264opts", "keyint=120:min-keyint=120:no-scenecut:open_gop=0",
        "-map_chapters", "-1",
        "-crf", String.valueOf(crf),
        "-maxrate", maxRate,
        "-bufsize", bufSize,
        "-preset", "veryslow",
        "-tune", "film",
        "-vf", vf,
        "-threads", String.valueOf(threads),
        outputPath.toString()
    );
    runProcess(cmd, null, onProcess);
  }

  private static void transcodeAudio(String inputPath, AudioTranscodeSpec spec,
                                     Path outputPath, Consumer<Process> onProcess) throws Exception {
    List<String> cmd = List.of(
        "ffmpeg", "-y",
        "-i", inputPath,
        "-map", "0:" + spec.stream(),
        "-ac", String.valueOf(spec.channels()),
        "-c", "aac",
        "-ab", spec.bitrateKbps() + "k",
        "-vn", "-sn",
        outputPath.toString()
    );
    runProcess(cmd, null, onProcess);
  }

  private static void transcodeSubtitle(TextTranscodeSpec spec, Path outputPath,
                                        Consumer<Process> onProcess) throws Exception {
    List<String> cmd = List.of(
        "ffmpeg", "-y",
        "-i", spec.src(),
        "-codec:s", "webvtt",
        outputPath.toString()
    );
    runProcess(cmd, null, onProcess);
  }

  // Workaround for https://github.com/shaka-project/shaka-packager/issues/1018
  private static void appendDummyCue(Path vttPath) throws IOException {
    String dummy = "\n99:00:00.000 --> 99:00:01.000\ndummy\n";
    Files.writeString(vttPath, dummy, StandardOpenOption.APPEND);
  }

  private static void stripShakaComments(Path dir, String glob) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
      for (Path file : stream) {
        List<String> lines = Files.readAllLines(file);
        List<String> filtered = lines.stream()
            .filter(line -> !line.contains("shaka-packager"))
            .toList();
        Files.write(file, filtered);
      }
    }
  }

  private static boolean isHdr(String inputPath) {
    try {
      ProcessBuilder pb = new ProcessBuilder(
          "ffprobe", "-v", "error",
          "-select_streams", "v:0",
          "-show_entries", "stream=color_transfer,color_space,color_primaries",
          "-of", "json",
          inputPath
      );
      pb.redirectErrorStream(true);
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes());
      process.waitFor();
      return output.contains("2020");
    } catch (Exception e) {
      log.warn("HDR detection failed, assuming SDR: {}", e.getMessage());
      return false;
    }
  }

  private static void runProcess(List<String> cmd, Path workDir,
                                 Consumer<Process> onProcess) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (workDir != null) pb.directory(workDir.toFile());
    pb.inheritIO();
    Process process = pb.start();
    onProcess.accept(process);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new RuntimeException("Process exited with code %d: %s".formatted(exitCode, String.join(" ", cmd)));
    }
  }
}
