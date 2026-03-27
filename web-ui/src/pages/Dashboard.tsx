import { Box, Typography, Grid, Card, CardContent } from '@mui/material';
import { useAuth } from '../hooks/useAuth';

export function Dashboard() {
  const { user } = useAuth();

  return (
    <Box>
      <Typography variant="h5" fontWeight={600} gutterBottom>
        Welcome back{user?.displayName ? `, ${user.displayName}` : ''}
      </Typography>
      <Typography variant="body2" color="text.secondary" mb={3}>
        Manage your transcoding jobs and API keys from here.
      </Typography>

      <Grid container spacing={2}>
        {[
          { label: 'Jobs submitted', value: '—' },
          { label: 'Jobs succeeded', value: '—' },
          { label: 'Jobs failed', value: '—' },
        ].map((stat) => (
          <Grid key={stat.label} size={{ xs: 12, sm: 4 }}>
            <Card variant="outlined">
              <CardContent>
                <Typography variant="h4" fontWeight={700}>
                  {stat.value}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {stat.label}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>
    </Box>
  );
}
