import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Button,
  TextField,
  Divider,
  Alert,
  CircularProgress,
} from '@mui/material';
import { useAuth } from '../hooks/useAuth';

export function Login() {
  const { user, loading, signInWithGoogle, signInWithGithub, sendMagicLink } = useAuth();
  const [email, setEmail] = useState('');
  const [emailSent, setEmailSent] = useState(false);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="100vh">
        <CircularProgress />
      </Box>
    );
  }

  if (user) {
    return <Navigate to="/dashboard" replace />;
  }

  const handleMagicLink = async () => {
    if (!email) {
      setError('Please enter your email address');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      await sendMagicLink(email);
      setEmailSent(true);
    } catch {
      setError('Failed to send sign-in link. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleSocialSignIn = async (provider: 'google' | 'github') => {
    setError('');
    try {
      if (provider === 'google') await signInWithGoogle();
      else await signInWithGithub();
    } catch {
      setError('Sign-in failed. Please try again.');
    }
  };

  return (
    <Box
      display="flex"
      justifyContent="center"
      alignItems="center"
      minHeight="100vh"
      bgcolor="background.default"
    >
      <Card sx={{ width: '100%', maxWidth: 420, mx: 2 }}>
        <CardContent sx={{ p: 4 }}>
          <Typography variant="h4" fontWeight={700} color="primary" gutterBottom>
            Streamforge
          </Typography>
          <Typography variant="body2" color="text.secondary" mb={3}>
            MPEG-DASH & HLS transcoding at scale
          </Typography>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          {emailSent ? (
            <Alert severity="success">
              Check your inbox — we sent a sign-in link to <strong>{email}</strong>.
            </Alert>
          ) : (
            <>
              <Button
                fullWidth
                variant="outlined"
                size="large"
                onClick={() => handleSocialSignIn('google')}
                sx={{ mb: 1.5 }}
              >
                Continue with Google
              </Button>
              <Button
                fullWidth
                variant="outlined"
                size="large"
                onClick={() => handleSocialSignIn('github')}
                sx={{ mb: 2 }}
              >
                Continue with GitHub
              </Button>

              <Divider sx={{ mb: 2 }}>or</Divider>

              <TextField
                fullWidth
                label="Email address"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleMagicLink()}
                sx={{ mb: 1.5 }}
              />
              <Button
                fullWidth
                variant="contained"
                size="large"
                onClick={handleMagicLink}
                disabled={submitting}
              >
                {submitting ? 'Sending…' : 'Send magic link'}
              </Button>
            </>
          )}
        </CardContent>
      </Card>
    </Box>
  );
}
