import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { readStored, writeStored } from './browser';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'petroflow.theme';

interface ThemeContextValue {
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

/** Saved choice wins; new workspaces start in the dark Ember & Evergreen theme. */
function initialTheme(): Theme {
  const saved = readStored(STORAGE_KEY);
  return saved === 'light' || saved === 'dark' ? saved : 'dark';
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(initialTheme);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = theme;
    root.style.colorScheme = theme;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#0a0c0b' : '#f4f3ef');
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    setThemeState(next);
    writeStored(STORAGE_KEY, next);
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeState((current) => {
      const next: Theme = current === 'dark' ? 'light' : 'dark';
      writeStored(STORAGE_KEY, next);
      return next;
    });
  }, []);

  const value = useMemo(() => ({ theme, setTheme, toggleTheme }), [theme, setTheme, toggleTheme]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
  return ctx;
}

/* ------------------------------------------------------------------ charts */

export interface ChartPalette {
  grid: string;
  axis: string;
  tooltipBg: string;
  tooltipBorder: string;
  tooltipLabel: string;
  accent: string;
  green: string;
  secondary: string;
  energy: string;
  warn: string;
  danger: string;
}

const CHART_DARK: ChartPalette = {
  grid: '#353a35',
  axis: '#a4ada3',
  tooltipBg: '#171a18',
  tooltipBorder: '#4f564f',
  tooltipLabel: '#f8f8f3',
  accent: '#ff9145',
  green: '#54d88e',
  secondary: '#ffac5e',
  energy: '#ffac5e',
  warn: '#efbe64',
  danger: '#ff7e87',
};

const CHART_LIGHT: ChartPalette = {
  grid: '#dad8d0',
  axis: '#5c635b',
  tooltipBg: '#fffefb',
  tooltipBorder: '#dad8d0',
  tooltipLabel: '#1c1f1c',
  accent: '#ae410c',
  green: '#0c7848',
  secondary: '#a45814',
  energy: '#a45814',
  warn: '#935b0f',
  danger: '#b22736',
};

export function useChartPalette(): ChartPalette {
  const { theme } = useTheme();
  return theme === 'light' ? CHART_LIGHT : CHART_DARK;
}
