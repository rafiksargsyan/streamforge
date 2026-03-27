import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './contexts/AuthContext';
import { ProtectedRoute } from '../components/ProtectedRoute/ProtectedRoute';
import { EmailConfirmation } from '../components/EmailConfirmation/EmailConfirmation';
import { Layout } from '../components/Layout/Layout';
import { Login } from '../pages/Login';
import { Dashboard } from '../pages/Dashboard';
import { Jobs } from '../pages/Jobs';
import { ApiKeys } from '../pages/ApiKeys';

export default function App() {
  return (
    <AuthProvider>
      <EmailConfirmation />
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="jobs" element={<Jobs />} />
            <Route path="api-keys" element={<ApiKeys />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
