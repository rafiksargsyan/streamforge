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
  DialogContentText,
  DialogActions,
  TextField,
  Alert,
  Chip,
  CircularProgress,
  Tooltip,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import { useAuth } from '../hooks/useAuth';
import { listApiKeys, createApiKey, disableApiKey, enableApiKey, deleteApiKey } from '../api/apiKeys';
import type { ApiKeyDTO } from '../types/api.types';

export function ApiKeys() {
  const { user, userId, accountId } = useAuth();
  const [apiKeys, setApiKeys] = useState<ApiKeyDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [createOpen, setCreateOpen] = useState(false);
  const [description, setDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [newKey, setNewKey] = useState<ApiKeyDTO | null>(null);

  const [deleteTarget, setDeleteTarget] = useState<ApiKeyDTO | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    if (!user || !userId || !accountId) return;
    try {
      const keys = await listApiKeys(user, userId, accountId);
      setApiKeys(keys);
    } catch (err) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [user, userId, accountId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleCreate = async () => {
    if (!user || !userId || !accountId || !description.trim()) return;
    setCreating(true);
    try {
      const key = await createApiKey(user, userId, accountId, description.trim());
      setNewKey(key);
      setDescription('');
      setCreateOpen(false);
      await load();
    } catch (err) {
      setError(String(err));
    } finally {
      setCreating(false);
    }
  };

  const handleToggle = async (key: ApiKeyDTO) => {
    if (!user || !userId || !accountId) return;
    try {
      if (key.enabled) {
        await disableApiKey(user, userId, accountId, key.id);
      } else {
        await enableApiKey(user, userId, accountId, key.id);
      }
      await load();
    } catch (err) {
      setError(String(err));
    }
  };

  const handleDelete = async () => {
    if (!user || !userId || !accountId || !deleteTarget) return;
    setDeleting(true);
    try {
      await deleteApiKey(user, userId, accountId, deleteTarget.id);
      setDeleteTarget(null);
      await load();
    } catch (err) {
      setError(String(err));
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
        <Typography variant="h5" fontWeight={600}>
          API Keys
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreateOpen(true)}
        >
          Create
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      {newKey?.key && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNewKey(null)}>
          <Typography variant="body2" fontWeight={600} gutterBottom>
            Copy your API key — it won't be shown again.
          </Typography>
          <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
            {newKey.key}
          </Typography>
        </Alert>
      )}

      {loading ? (
        <Box display="flex" justifyContent="center" mt={4}>
          <CircularProgress />
        </Box>
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Description</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Created</TableCell>
                <TableCell>Last used</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {apiKeys.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} align="center">
                    <Typography variant="body2" color="text.secondary" py={2}>
                      No API keys yet.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {apiKeys.map((key) => (
                <TableRow key={key.id}>
                  <TableCell>{key.description}</TableCell>
                  <TableCell>
                    <Chip
                      label={key.enabled ? 'Enabled' : 'Disabled'}
                      color={key.enabled ? 'success' : 'default'}
                      size="small"
                      onClick={() => handleToggle(key)}
                      clickable
                    />
                  </TableCell>
                  <TableCell>{new Date(key.createdAt).toLocaleDateString()}</TableCell>
                  <TableCell>
                    {key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleDateString() : '—'}
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title={key.enabled ? 'Disable before deleting' : 'Delete'}>
                      <span>
                        <IconButton
                          size="small"
                          color="error"
                          disabled={key.enabled}
                          onClick={() => setDeleteTarget(key)}
                        >
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* Create dialog */}
      <Dialog open={createOpen} onClose={() => setCreateOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Create API Key</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            label="Description"
            fullWidth
            variant="outlined"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={handleCreate} disabled={creating || !description.trim()}>
            {creating ? 'Creating…' : 'Create'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete dialog */}
      <Dialog open={Boolean(deleteTarget)} onClose={() => setDeleteTarget(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete API Key</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Delete <strong>{deleteTarget?.description}</strong>? This cannot be undone.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>Cancel</Button>
          <Button variant="contained" color="error" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
