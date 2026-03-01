import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loading } from '@carbon/react';
import { useAuthStore } from '../stores/authStore';

export default function AuthCallback() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { login } = useAuthStore();

  useEffect(() => {
    const accessToken = params.get('access_token');
    const brdgId = params.get('brdg_id');
    const error = params.get('error');

    if (error) {
      navigate(`/login?error=${error}`);
      return;
    }

    if (accessToken && brdgId) {
      login(accessToken, brdgId, '');
      navigate('/dashboard');
    } else {
      navigate('/login?error=missing_tokens');
    }
  }, [params, login, navigate]);

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
    }}>
      <Loading withOverlay={false} description="Completing sign in..." />
    </div>
  );
}
