import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Grid,
  Column,
  Tile,
  TextInput,
  PasswordInput,
  Button,
  InlineNotification,
  Loading,
} from '@carbon/react';
import { Login, UserOnline } from '@carbon/icons-react';
import { authApi } from '../services/authApi';
import { useAuthStore } from '../stores/authStore';

const GATEWAY_CALLBACK_PATH = '/auth/callback';

export default function LoginPage() {
  const navigate = useNavigate();
  const { login } = useAuthStore();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleGoogleLogin = () => {
    const callbackUrl = `${window.location.origin}${GATEWAY_CALLBACK_PATH}`;
    window.location.href = authApi.getSocialAuthUrl('google', callbackUrl);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await authApi.login({ email, password });
      login(res.access_token, res.brdg_id, email);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Invalid credentials');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Grid condensed style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <Column lg={5} md={6} sm={4}>
        <Tile style={{ padding: '2rem' }}>
          <h2 style={{ marginBottom: '0.5rem' }}>Bridge Gateway</h2>
          <p style={{ color: '#8d8d8d', marginBottom: '2rem', fontSize: '0.875rem' }}>
            Sign in with your Bridge ID
          </p>
          {error && (
            <InlineNotification
              kind="error"
              title="Error"
              subtitle={error}
              onCloseButtonClick={() => setError('')}
              style={{ marginBottom: '1rem' }}
            />
          )}
          <Button
            kind="tertiary"
            renderIcon={UserOnline}
            onClick={handleGoogleLogin}
            style={{ width: '100%', marginBottom: '1rem' }}
          >
            Sign in with Google
          </Button>
          <div style={{ display: 'flex', alignItems: 'center', margin: '1rem 0' }}>
            <div style={{ flex: 1, height: '1px', backgroundColor: '#393939' }} />
            <span style={{ padding: '0 1rem', fontSize: '0.75rem', color: '#6f6f6f' }}>or</span>
            <div style={{ flex: 1, height: '1px', backgroundColor: '#393939' }} />
          </div>
          <form onSubmit={handleSubmit}>
            <TextInput
              id="email"
              labelText="Email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              style={{ marginBottom: '1rem' }}
            />
            <PasswordInput
              id="password"
              labelText="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              style={{ marginBottom: '1.5rem' }}
            />
            <Button
              type="submit"
              renderIcon={Login}
              disabled={loading}
              style={{ width: '100%' }}
            >
              {loading ? 'Signing in...' : 'Sign In'}
            </Button>
          </form>
        </Tile>
      </Column>
      {loading && <Loading withOverlay />}
    </Grid>
  );
}
