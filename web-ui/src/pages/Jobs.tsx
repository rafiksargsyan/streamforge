import { useState, useEffect, useCallback } from 'react';
import {
  Box,
  Typography,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  IconButton,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Alert,
  Chip,
  CircularProgress,
  Pagination,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  Tooltip,
  Divider,
  Stack,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import CancelIcon from '@mui/icons-material/Cancel';
import DeleteIcon from '@mui/icons-material/Delete';
import LinkIcon from '@mui/icons-material/Link';
import { useAuth } from '../hooks/useAuth';
import { listJobs, createJob, cancelJob } from '../api/jobs';
import type {
  TranscodingJobDTO,
  JobStatus,
  Lang,
  VideoRendition,
  AudioTranscodeSpec,
  TextTranscodeSpec,
} from '../types/api.types';

const LANGS: Lang[] = [
  'EN', 'ES', 'FR', 'DE', 'IT', 'PT', 'RU', 'ZH', 'JA', 'KO',
  'AR', 'HI', 'BN', 'TR', 'PL', 'NL', 'SV', 'NO', 'DA', 'FI',
  'CS', 'SK', 'HU', 'RO', 'UK', 'EL', 'HE', 'FA', 'ID', 'MS',
  'TH', 'VI', 'SR', 'HR', 'BG', 'LT', 'LV', 'ET',
  'BE', 'HY', 'MK', 'NB', 'SL',
];

const PAGE_SIZE = 10;

const STATUS_COLOR: Record<JobStatus, 'default' | 'info' | 'warning' | 'success' | 'error'> = {
  SUBMITTED: 'info',
  QUEUED: 'info',
  RECEIVED: 'info',
  IN_PROGRESS: 'warning',
  RETRYING: 'warning',
  SUCCESS: 'success',
  FAILURE: 'error',
  CANCELLED: 'default',
};

const TERMINAL: JobStatus[] = ['SUCCESS', 'FAILURE', 'CANCELLED'];

export function Jobs() {
  const { user, accountId } = useAuth();
  const [jobs, setJobs] = useState<TranscodingJobDTO[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Create dialog state
  const [createOpen, setCreateOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [videoURL, setVideoURL] = useState('');
  const [videoStream, setVideoStream] = useState(0);
  const [renditions, setRenditions] = useState<VideoRendition[]>([
    { resolution: 1080, fileName: '1080p.mp4' },
  ]);
  const [audios, setAudios] = useState<AudioTranscodeSpec[]>([
    { stream: 1, bitrateKbps: 128, channels: 2, lang: 'EN', name: 'English', fileName: 'audio_en.mp4' },
  ]);
  const [texts, setTexts] = useState<TextTranscodeSpec[]>([]);

  // Cancel state
  const [cancelTarget, setCancelTarget] = useState<TranscodingJobDTO | null>(null);
  const [cancelling, setCancelling] = useState(false);

  const load = useCallback(async () => {
    if (!user || !accountId) return;
    setLoading(true);
    try {
      const result = await listJobs(user, accountId, page - 1, PAGE_SIZE);
      setJobs(result.content);
      setTotalPages(result.totalPages);
    } catch (err) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [user, accountId, page]);

  useEffect(() => {
    load();
  }, [load]);

  const resetCreateForm = () => {
    setVideoURL('');
    setVideoStream(0);
    setRenditions([{ resolution: 1080, fileName: '1080p.mp4' }]);
    setAudios([{ stream: 1, bitrateKbps: 128, channels: 2, lang: 'EN', name: 'English', fileName: 'audio_en.mp4' }]);
    setTexts([]);
  };

  const handleCreate = async () => {
    if (!user || !accountId || !videoURL.trim()) return;
    setSubmitting(true);
    try {
      await createJob(user, accountId, {
        videoURL: videoURL.trim(),
        spec: { video: { stream: videoStream, renditions }, audios, texts },
      });
      setCreateOpen(false);
      resetCreateForm();
      setPage(1);
      await load();
    } catch (err) {
      setError(String(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = async () => {
    if (!user || !accountId || !cancelTarget) return;
    setCancelling(true);
    try {
      await cancelJob(user, accountId, cancelTarget.id);
      setCancelTarget(null);
      await load();
    } catch (err) {
      setError(String(err));
    } finally {
      setCancelling(false);
    }
  };

  // Rendition helpers
  const addRendition = () =>
    setRenditions((r) => [...r, { resolution: 720, fileName: '720p.mp4' }]);
  const updateRendition = <K extends keyof VideoRendition>(i: number, field: K, value: VideoRendition[K]) =>
    setRenditions((r) => r.map((v, idx) => (idx === i ? { ...v, [field]: value } : v)));
  const removeRendition = (i: number) =>
    setRenditions((r) => r.filter((_, idx) => idx !== i));

  // Audio helpers
  const addAudio = () =>
    setAudios((a) => [...a, { stream: 1, bitrateKbps: 128, channels: 2, lang: 'EN', name: '', fileName: '' }]);
  const updateAudio = <K extends keyof AudioTranscodeSpec>(
    i: number,
    field: K,
    value: AudioTranscodeSpec[K],
  ) => setAudios((a) => a.map((v, idx) => (idx === i ? { ...v, [field]: value } : v)));
  const removeAudio = (i: number) =>
    setAudios((a) => a.filter((_, idx) => idx !== i));

  // Text helpers
  const addText = () =>
    setTexts((t) => [...t, { lang: 'EN', name: '', fileName: '', src: '' }]);
  const updateText = <K extends keyof TextTranscodeSpec>(
    i: number,
    field: K,
    value: TextTranscodeSpec[K],
  ) => setTexts((t) => t.map((v, idx) => (idx === i ? { ...v, [field]: value } : v)));
  const removeText = (i: number) =>
    setTexts((t) => t.filter((_, idx) => idx !== i));

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
        <Typography variant="h5" fontWeight={600}>
          Jobs
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => { resetCreateForm(); setCreateOpen(true); }}
        >
          New Job
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      {loading ? (
        <Box display="flex" justifyContent="center" mt={4}>
          <CircularProgress />
        </Box>
      ) : (
        <>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>ID</TableCell>
                  <TableCell>Video URL</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell>Output</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {jobs.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={6} align="center">
                      <Typography variant="body2" color="text.secondary" py={2}>
                        No jobs yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {jobs.map((job) => (
                  <TableRow key={job.id}>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>
                      {job.id.slice(0, 8)}…
                    </TableCell>
                    <TableCell
                      sx={{
                        maxWidth: 200,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <Tooltip title={job.videoUrl}>
                        <span>{job.videoUrl}</span>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={job.status}
                        color={STATUS_COLOR[job.status]}
                        size="small"
                      />
                    </TableCell>
                    <TableCell>{new Date(job.createdAt).toLocaleString()}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5}>
                        {job.dashManifestUrl && (
                          <Tooltip title="DASH manifest">
                            <IconButton
                              size="small"
                              component="a"
                              href={job.dashManifestUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                            >
                              <LinkIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                        {job.hlsManifestUrl && (
                          <Tooltip title="HLS manifest">
                            <IconButton
                              size="small"
                              component="a"
                              href={job.hlsManifestUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              color="secondary"
                            >
                              <LinkIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                      </Stack>
                    </TableCell>
                    <TableCell align="right">
                      {!TERMINAL.includes(job.status) && (
                        <Tooltip title="Cancel">
                          <IconButton
                            size="small"
                            color="error"
                            onClick={() => setCancelTarget(job)}
                          >
                            <CancelIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          {totalPages > 1 && (
            <Box display="flex" justifyContent="center" mt={2}>
              <Pagination
                count={totalPages}
                page={page}
                onChange={(_, v) => setPage(v)}
              />
            </Box>
          )}
        </>
      )}

      {/* Create dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>New Transcoding Job</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Video URL"
              fullWidth
              value={videoURL}
              onChange={(e) => setVideoURL(e.target.value)}
              placeholder="https://example.com/video.mp4"
            />

            <Divider>Video (H.264)</Divider>

            <TextField
              label="Video stream index"
              type="number"
              size="small"
              value={videoStream}
              onChange={(e) => setVideoStream(Number(e.target.value))}
              sx={{ width: 180 }}
            />

            {renditions.map((r, i) => (
              <Stack key={i} direction="row" spacing={1} alignItems="center">
                <TextField
                  label="Resolution (px)"
                  type="number"
                  size="small"
                  value={r.resolution}
                  onChange={(e) => updateRendition(i, 'resolution', Number(e.target.value))}
                  sx={{ flex: 1 }}
                  placeholder="360, 480, 720, 1080"
                />
                <TextField
                  label="File name"
                  size="small"
                  value={r.fileName}
                  onChange={(e) => updateRendition(i, 'fileName', e.target.value)}
                  sx={{ flex: 1 }}
                  placeholder="1080p.mp4"
                />
                <IconButton size="small" onClick={() => removeRendition(i)} disabled={renditions.length === 1}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}
            <Button size="small" onClick={addRendition}>+ Add rendition</Button>

            <Divider>Audio tracks</Divider>

            {audios.map((a, i) => (
              <Box key={i} sx={{ pt: i > 0 ? 1.5 : 0, pb: 1.5, borderTop: i > 0 ? '1px solid' : 'none', borderColor: 'divider' }}>
                <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
                  <TextField
                    label="Stream index"
                    type="number"
                    size="small"
                    value={a.stream}
                    onChange={(e) => updateAudio(i, 'stream', Number(e.target.value))}
                    sx={{ width: 110 }}
                  />
                  <TextField
                    label="Bitrate (kbps)"
                    type="number"
                    size="small"
                    value={a.bitrateKbps}
                    onChange={(e) => updateAudio(i, 'bitrateKbps', Number(e.target.value))}
                    sx={{ width: 120 }}
                  />
                  <TextField
                    label="Channels"
                    type="number"
                    size="small"
                    value={a.channels}
                    onChange={(e) => updateAudio(i, 'channels', Number(e.target.value))}
                    sx={{ width: 90 }}
                  />
                  <FormControl size="small" sx={{ width: 90 }}>
                    <InputLabel>Lang</InputLabel>
                    <Select
                      label="Lang"
                      value={a.lang}
                      onChange={(e) => updateAudio(i, 'lang', e.target.value as Lang)}
                    >
                      {LANGS.map((l) => (
                        <MenuItem key={l} value={l}>{l}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                  <IconButton size="small" onClick={() => removeAudio(i)} disabled={audios.length === 1} sx={{ ml: 'auto' }}>
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Stack>
                <Stack direction="row" spacing={1} alignItems="center">
                  <TextField
                    label="Track name"
                    size="small"
                    value={a.name}
                    onChange={(e) => updateAudio(i, 'name', e.target.value)}
                    sx={{ flex: 1 }}
                  />
                  <TextField
                    label="File name"
                    size="small"
                    value={a.fileName}
                    onChange={(e) => updateAudio(i, 'fileName', e.target.value)}
                    placeholder="audio_en.mp4"
                    sx={{ flex: 1 }}
                  />
                </Stack>
              </Box>
            ))}
            <Button size="small" onClick={addAudio}>+ Add audio track</Button>

            <Divider>Subtitles (optional)</Divider>

            {texts.map((t, i) => (
              <Stack key={i} direction="row" spacing={1} alignItems="center">
                <FormControl size="small" sx={{ minWidth: 80 }}>
                  <InputLabel>Lang</InputLabel>
                  <Select
                    label="Lang"
                    value={t.lang}
                    onChange={(e) => updateText(i, 'lang', e.target.value as Lang)}
                  >
                    {LANGS.map((l) => (
                      <MenuItem key={l} value={l}>{l}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <TextField
                  label="Track name"
                  size="small"
                  value={t.name}
                  onChange={(e) => updateText(i, 'name', e.target.value)}
                  sx={{ flex: 1 }}
                />
                <TextField
                  label="File name"
                  size="small"
                  value={t.fileName}
                  onChange={(e) => updateText(i, 'fileName', e.target.value)}
                  placeholder="subtitle_en.vtt"
                  sx={{ flex: 1 }}
                />
                <TextField
                  label="Subtitle URL"
                  size="small"
                  value={t.src}
                  onChange={(e) => updateText(i, 'src', e.target.value)}
                  sx={{ flex: 2 }}
                />
                <IconButton size="small" onClick={() => removeText(i)}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Stack>
            ))}
            <Button size="small" onClick={addText}>+ Add subtitle track</Button>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={handleCreate}
            disabled={submitting || !videoURL.trim()}
          >
            {submitting ? 'Submitting…' : 'Submit'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Cancel confirm dialog */}
      <Dialog open={Boolean(cancelTarget)} onClose={() => setCancelTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Cancel Job</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            Cancel job <strong>{cancelTarget?.id.slice(0, 8)}…</strong>?
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCancelTarget(null)}>No</Button>
          <Button variant="contained" color="error" onClick={handleCancel} disabled={cancelling}>
            {cancelling ? 'Cancelling…' : 'Yes, cancel'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
