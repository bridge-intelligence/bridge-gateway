import { create } from 'zustand';

interface AuthState {
  accessToken: string | null;
  brdgId: string | null;
  email: string | null;
  isAuthenticated: boolean;
  login: (token: string, brdgId: string, email: string) => void;
  logout: () => void;
  loadFromStorage: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  brdgId: null,
  email: null,
  isAuthenticated: false,

  login: (token, brdgId, email) => {
    localStorage.setItem('bridge_access_token', token);
    localStorage.setItem('bridge_brdg_id', brdgId);
    localStorage.setItem('bridge_email', email);
    set({ accessToken: token, brdgId, email, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('bridge_access_token');
    localStorage.removeItem('bridge_brdg_id');
    localStorage.removeItem('bridge_email');
    set({ accessToken: null, brdgId: null, email: null, isAuthenticated: false });
  },

  loadFromStorage: () => {
    const token = localStorage.getItem('bridge_access_token');
    const brdgId = localStorage.getItem('bridge_brdg_id');
    const email = localStorage.getItem('bridge_email');
    if (token && brdgId) {
      set({ accessToken: token, brdgId, email, isAuthenticated: true });
    }
  },
}));
