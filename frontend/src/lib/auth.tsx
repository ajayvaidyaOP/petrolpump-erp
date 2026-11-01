import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, apiGet, apiPost, setPreviewSessionToken } from './api';
import { readStored, writeStored } from './browser';

export interface Station {
  id: string;
  name: string;
  code: string;
  status?: string;
}

export interface AuthUser {
  id: string;
  name: string;
  email: string;
  phone?: string | null;
  roleKey: string;
  roleName: string;
  tenantId: string | null;
  tenantName?: string | null;
}

export interface Me {
  user: AuthUser;
  tenant: { id: string; name: string; slug: string; status: string; isDemo: number } | null;
  subscription: {
    status: string;
    planName: string;
    planCode: string;
    trialEndsAt: string | null;
    endsAt: string | null;
    maxStations: number;
    maxUsers: number;
  } | null;
  stations: Station[];
  permissions: string[];
  roleScope: string;
  isSuperAdmin: boolean;
  demoMode: boolean;
}

interface AuthContextValue {
  me: Me | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  can: (permission: string) => boolean;
  refresh: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const meQuery = useQuery<Me>({
    queryKey: ['me'],
    queryFn: () => apiGet<Me>('/auth/me'),
    retry: false,
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });

  const login = useCallback(
    async (email: string, password: string) => {
      // Clear a stale fallback before authenticating a new user.
      setPreviewSessionToken(null);
      const loginResult = await apiPost<{ sessionToken?: string }>('/auth/login', { email, password });
      try {
        const me = await queryClient.fetchQuery({ queryKey: ['me'], queryFn: () => apiGet<Me>('/auth/me') });
        setPreviewSessionToken(null);
        return me;
      } catch (error) {
        if (error instanceof ApiError && error.status === 401 && loginResult.sessionToken) {
          // Some embedded browsers reject even partitioned cookies. Retry once
          // with a tab-scoped bearer session issued only for the dev preview.
          setPreviewSessionToken(loginResult.sessionToken, true);
          try {
            const me = await apiGet<Me>('/auth/me');
            queryClient.setQueryData(['me'], me);
            return me;
          } catch (fallbackError) {
            setPreviewSessionToken(null);
            throw fallbackError;
          }
        }
        if (error instanceof ApiError && error.status === 401) {
          throw new Error('Sign-in succeeded, but the session could not be established. Please retry signing in.');
        }
        throw error;
      }
    },
    [queryClient],
  );

  const logout = useCallback(async () => {
    try {
      await apiPost('/auth/logout');
    } finally {
      setPreviewSessionToken(null);
      queryClient.clear();
      window.location.href = '/login';
    }
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(() => {
    const me = meQuery.data ?? null;
    return {
      me,
      isLoading: meQuery.isLoading,
      isAuthenticated: Boolean(me),
      login,
      logout,
      can: (permission: string) => Boolean(me?.permissions.includes(permission) || me?.isSuperAdmin),
      refresh: () => void queryClient.invalidateQueries({ queryKey: ['me'] }),
    };
  }, [meQuery.data, meQuery.isLoading, login, logout, queryClient]);

  return (
    <AuthContext.Provider value={value}>
      <StationProvider>{children}</StationProvider>
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

export function useLoginMutation() {
  const { login } = useAuth();
  return useMutation({
    mutationFn: ({ email, password }: { email: string; password: string }) => login(email, password),
  });
}

/* --------------------------------------------------------------- station */
const STORAGE_KEY = 'petroflow.station';

interface StationContextValue {
  stations: Station[];
  station: Station | null;
  stationId: string;
  selectStation: (id: string) => void;
}

const StationContext = createContext<StationContextValue | null>(null);

function StationProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const stations = useMemo(() => me?.stations ?? [], [me]);
  const [stationId, setStationId] = useState<string>(() => readStored(STORAGE_KEY) ?? '');

  useEffect(() => {
    if (stations.length === 0) return;
    if (!stationId || !stations.some((s) => s.id === stationId)) {
      const next = stations[0].id;
      setStationId(next);
      writeStored(STORAGE_KEY, next);
    }
  }, [stations, stationId]);

  // Keep another open tab in sync as well as every component in this tab.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) setStationId(event.newValue ?? '');
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const selectStation = useCallback((id: string) => {
    setStationId(id);
    writeStored(STORAGE_KEY, id);
    window.dispatchEvent(new CustomEvent('petroflow:station-changed', { detail: id }));
  }, []);

  const station = stations.find((s) => s.id === stationId) ?? stations[0] ?? null;
  const value = useMemo(
    () => ({ stations, station, stationId: station?.id ?? '', selectStation }),
    [stations, station, selectStation],
  );
  return <StationContext.Provider value={value}>{children}</StationContext.Provider>;
}

export function useStations() {
  const context = useContext(StationContext);
  if (!context) throw new Error('useStations must be used inside AuthProvider');
  return context;
}
