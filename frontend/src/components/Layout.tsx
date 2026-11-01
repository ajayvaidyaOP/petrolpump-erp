import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  Activity,
  BarChart3,
  Building2,
  Calendar,
  ChevronDown,
  ChevronRight,
  CreditCard,
  Database,
  FileBadge2,
  Fuel,
  HardDrive,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Menu,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Receipt,
  Scale,
  ScrollText,
  Search,
  Settings as SettingsIcon,
  ShieldAlert,
  ShieldCheck,
  Store,
  Sun,
  Tags,
  Thermometer,
  Truck,
  Users,
  Wallet,
  X,
} from 'lucide-react';
import { useAuth, useStations } from '../lib/auth';
import { useTheme } from '../lib/theme';
import { activeSection, canOpen, navigation, type NavigationGroup } from '../lib/navigation';
import { useAttentionItems } from '../lib/alerts';
import { cn } from '../lib/utils';
import { formatDate, initials, todayIst } from '../lib/format';
import { Button, Field, Input, Modal, useToast } from './ui';
import { NotificationCenter } from './NotificationCenter';
import { RouteErrorBoundary } from './RouteErrorBoundary';
import { CommandPalette } from './CommandPalette';
import { apiPost } from '../lib/api';
import { readStored, writeStored, mediaQuery } from '../lib/browser';

const SIDEBAR_STORAGE_KEY = 'petroflow.sidebar-collapsed';

const primaryIcons: Record<string, ReactNode> = {
  '/': <LayoutDashboard />,
  '/shifts': <ListChecks />,
  '/tanks': <Database />,
  '/sales': <Fuel />,
  '/finance': <Wallet />,
  '/reports': <BarChart3 />,
  '/employees': <Users />,
  '/settings': <SettingsIcon />,
};

const childIcons: Record<string, ReactNode> = {
  '/shifts': <ListChecks />,
  '/readings': <Activity />,
  '/density': <Thermometer />,
  '/tanks': <Database />,
  '/deliveries': <Truck />,
  '/lubricants': <Fuel />,
  '/sales': <Fuel />,
  '/customers': <Users />,
  '/finance': <Wallet />,
  '/suppliers': <Truck />,
  '/reconciliation': <Scale />,
  '/settings': <SettingsIcon />,
  '/stations': <Store />,
  '/products': <Tags />,
  '/dispensers': <Fuel />,
  '/devices': <HardDrive />,
  '/compliance': <FileBadge2 />,
  '/audit': <ScrollText />,
  '/billing': <CreditCard />,
};

const adminGroups = [
  {
    group: 'SaaS Platform',
    items: [
      { to: '/admin', label: 'Platform overview', icon: <LayoutDashboard /> },
      { to: '/admin/tenants', label: 'Organizations', icon: <Building2 /> },
      { to: '/admin/stations', label: 'Stations', icon: <Store /> },
      { to: '/admin/subscriptions', label: 'Subscriptions', icon: <Receipt /> },
    ],
  },
  {
    group: 'Infrastructure & Audit',
    items: [
      { to: '/admin/devices', label: 'Hardware fleet', icon: <HardDrive /> },
      { to: '/admin/errors', label: 'Platform errors', icon: <ShieldAlert /> },
      { to: '/admin/audit', label: 'Audit trail', icon: <ScrollText /> },
    ],
  },
];

const GROUP_ORDER: NavigationGroup[] = [
  'Forecourt Operations',
  'Sales & Accounting',
  'Workspace & Setup',
];

function Brand({ collapsed = false }: { collapsed?: boolean }) {
  return (
    <div className={cn('flex items-center gap-3', collapsed && 'justify-center')}>
      <span className="brand-gradient relative grid h-10 w-10 shrink-0 place-items-center overflow-hidden rounded-xl text-onbrand shadow-glow ring-1 ring-accent/30">
        <span aria-hidden className="absolute inset-0 bg-[linear-gradient(145deg,rgb(255_255_255/0.32),transparent_55%)]" />
        <Fuel className="relative h-[19px] w-[19px]" strokeWidth={2.3} aria-hidden />
        <span aria-hidden className="absolute bottom-1 right-1 h-1.5 w-1.5 rounded-full bg-[#247c48] ring-1 ring-[#241308]/40" />
      </span>
      {!collapsed && (
        <div className="min-w-0">
          <p className="font-display text-[19px] font-extrabold leading-none tracking-[-0.03em] text-ink-100">
            Petro<span className="text-fuel">Flow</span>
          </p>
          <p className="mt-1 text-[9.5px] font-bold uppercase tracking-[0.17em] text-ink-500">
            STATION ERP
          </p>
        </div>
      )}
    </div>
  );
}

function PrimaryNav({
  collapsed = false,
  onNavigate,
}: {
  collapsed?: boolean;
  onNavigate?: () => void;
}) {
  const { can, me } = useAuth();
  const { pathname } = useLocation();

  if (me?.isSuperAdmin) {
    return (
      <nav aria-label="Primary" className="flex-1 space-y-6 overflow-y-auto px-3 py-6 scroll-thin">
        {adminGroups.map((group) => (
          <div key={group.group}>
            {!collapsed && (
              <p className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.14em] text-ink-500">
                {group.group}
              </p>
            )}
            <div className="space-y-0.5">
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/admin'}
                  title={collapsed ? item.label : undefined}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    cn(
                      'nav-item',
                      collapsed && 'justify-center px-2',
                      isActive && 'nav-item-active',
                    )
                  }
                >
                  <span className="nav-icon [&>svg]:h-[18px] [&>svg]:w-[18px]" aria-hidden>
                    {item.icon}
                  </span>
                  <span className={cn(collapsed ? 'sr-only' : 'truncate')}>{item.label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
    );
  }

  const permitted = navigation.filter((item) => canOpen(can, item));

  return (
    <nav aria-label="Primary" className="flex-1 space-y-6 overflow-y-auto px-3 py-6 scroll-thin">
      {GROUP_ORDER.map((groupName) => {
        const items = permitted.filter((item) => item.group === groupName);
        if (items.length === 0) return null;
        return (
          <div key={groupName}>
            {!collapsed && (
              <p className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.14em] text-ink-500">
                {groupName}
              </p>
            )}
            <div className="space-y-0.5">
              {items.map((item) => {
                const active = activeSection(pathname)?.to === item.to;
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.end}
                    title={collapsed ? item.label : undefined}
                    onClick={onNavigate}
                    className={cn(
                      'nav-item',
                      collapsed && 'justify-center px-2',
                      active && 'nav-item-active',
                    )}
                    aria-current={active ? 'page' : undefined}
                  >
                    <span className="nav-icon [&>svg]:h-[18px] [&>svg]:w-[18px]" aria-hidden>
                      {primaryIcons[item.to]}
                    </span>
                    <span className={cn(collapsed ? 'sr-only' : 'truncate')}>{item.label}</span>
                    {!collapsed && active && (
                      <ChevronRight className="ml-auto h-3.5 w-3.5 opacity-80" aria-hidden />
                    )}
                  </NavLink>
                );
              })}
            </div>
          </div>
        );
      })}
    </nav>
  );
}

function SectionNav() {
  const { can } = useAuth();
  const { pathname } = useLocation();
  const section = activeSection(pathname);
  if (!section || section.children.length < 2) return null;
  const children = section.children.filter((item) => canOpen(can, item));
  if (children.length < 2) return null;
  return (
    <nav
      aria-label={`${section.label} sections`}
      className="mb-6 flex max-w-full items-center gap-1 overflow-x-auto rounded-2xl border border-carbon-700/80 bg-carbon-900/90 p-1.5 shadow-card backdrop-blur-md scroll-thin"
    >
      {children.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          className={({ isActive }) =>
            cn(
              'inline-flex items-center gap-2 whitespace-nowrap rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all duration-150',
              isActive
                ? 'border border-accent/30 bg-accent/12 text-accent shadow-sm'
                : 'border border-transparent text-ink-400 hover:bg-carbon-800/85 hover:text-ink-100',
            )
          }
        >
          {childIcons[item.to] && (
            <span className="shrink-0 opacity-90 [&>svg]:h-3.5 [&>svg]:w-3.5" aria-hidden>
              {childIcons[item.to]}
            </span>
          )}
          <span>{item.label}</span>
        </NavLink>
      ))}
    </nav>
  );
}

function MobileNav({ onMenu }: { onMenu: () => void }) {
  const { can } = useAuth();
  const { pathname } = useLocation();
  const items = navigation.filter((item) => canOpen(can, item)).slice(0, 4);
  return (
    <nav
      aria-label="Quick navigation"
      className="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-carbon-700/80 bg-carbon-900/95 px-1 pb-[env(safe-area-inset-bottom)] shadow-[0_-8px_24px_-12px_rgb(0_0_0/0.2)] backdrop-blur-xl md:hidden"
    >
      {items.map((item) => {
        const active = activeSection(pathname)?.to === item.to;
        return (
          <NavLink
            key={item.to}
            to={item.to}
            className={cn(
              'relative flex min-h-16 flex-col items-center justify-center gap-1 text-[10px] font-semibold transition-colors',
              active ? 'text-accent' : 'text-ink-500',
            )}
          >
            {active && (
              <span className="brand-gradient absolute inset-x-4 top-0 h-[2.5px] rounded-b-full" aria-hidden />
            )}
            <span className={cn('rounded-xl px-2.5 py-1 transition-colors [&>svg]:h-[19px] [&>svg]:w-[19px]', active && 'bg-accent/12')}>
              {primaryIcons[item.to]}
            </span>
            {item.to === '/' ? 'Home' : item.to === '/shifts' ? 'Shifts' : item.to === '/tanks' ? 'Stock' : 'Sales'}
          </NavLink>
        );
      })}
      <button
        type="button"
        onClick={onMenu}
        className="flex flex-col items-center justify-center gap-1 text-[10px] font-semibold text-ink-500 transition hover:text-ink-200"
      >
        <span className="rounded-xl px-2.5 py-1">
          <Menu className="h-[19px] w-[19px]" />
        </span>
        More
      </button>
    </nav>
  );
}

function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  const label = theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme';
  return (
    <button
      type="button"
      onClick={toggleTheme}
      title={label}
      aria-label={label}
      className="grid h-9.5 w-9.5 shrink-0 place-items-center rounded-xl border border-carbon-700/85 bg-carbon-900 text-ink-400 shadow-sheen transition-all duration-150 hover:border-carbon-600 hover:bg-carbon-800 hover:text-ink-100"
    >
      {theme === 'dark' ? <Sun className="h-[17px] w-[17px]" /> : <Moon className="h-[17px] w-[17px]" />}
    </button>
  );
}

function HeaderShiftContext() {
  const { me, can } = useAuth();
  const { stationId } = useStations();
  const attention = useAttentionItems(me?.isSuperAdmin ? null : stationId);

  if (me?.isSuperAdmin || !stationId || !can('reports.read')) return null;

  const openShift = attention.openShifts[0] as { shiftNumber?: number } | undefined;
  const businessDate = attention.businessDate || todayIst();

  return (
    <Link
      to="/shifts"
      className="hidden items-center gap-2 rounded-xl border border-carbon-700/85 bg-carbon-900 px-3 py-1.5 text-xs shadow-sheen transition hover:border-accent/45 hover:bg-carbon-800/80 2xl:inline-flex"
      title="Business date (IST) and active forecourt shift"
    >
      <Calendar className="h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
      <span className="num font-medium text-ink-300">{formatDate(businessDate)}</span>
      <span className="h-3.5 w-px bg-carbon-700" aria-hidden />
      <span className="flex items-center gap-1.5 font-semibold">
        <span
          className={cn(
            'h-2 w-2 rounded-full',
            openShift ? 'animate-pulse-soft bg-ok shadow-[0_0_0_3px_rgb(var(--ok)/0.18)]' : 'bg-ink-600',
          )}
          aria-hidden
        />
        <span className={openShift ? 'text-ink-100' : 'text-ink-400'}>
          {openShift ? `Shift #${String(openShift.shiftNumber)} Live` : 'No Open Shift'}
        </span>
      </span>
    </Link>
  );
}

export function Layout() {
  const { me, logout } = useAuth();
  const { stations, station, selectStation } = useStations();
  const [collapsed, setCollapsed] = useState<boolean>(() => readStored(SIDEBAR_STORAGE_KEY) === '1');
  const [mobileOpen, setMobileOpen] = useState(false);
  const [userMenu, setUserMenu] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const drawerRef = useRef<HTMLElement>(null);
  const location = useLocation();
  const { toast } = useToast();
  const isDemoTenant = Boolean(me?.tenant?.isDemo);
  const section = activeSection(location.pathname);
  const activeChild = section?.children.find((child) => child.to === location.pathname);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      writeStored(SIDEBAR_STORAGE_KEY, next ? '1' : '0');
      return next;
    });
  };

  useEffect(() => {
    setMobileOpen(false);
    setUserMenu(false);
  }, [location.pathname]);

  useEffect(() => {
    const handler = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setUserMenu(false);
    };
    const key = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setCommandOpen((prev) => !prev);
        return;
      }
      if (event.key === 'Escape') {
        setUserMenu(false);
        setMobileOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', key);
    };
  }, []);

  useEffect(() => {
    if (!mobileOpen) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    drawerRef.current?.querySelector<HTMLElement>('button')?.focus();
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const items = drawerRef.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), select:not([disabled])',
      );
      if (!items?.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    const media = mediaQuery('(min-width: 768px)');
    const onDesktop = () => {
      if (media?.matches) setMobileOpen(false);
    };
    media?.addEventListener('change', onDesktop);
    document.addEventListener('keydown', trap);
    return () => {
      document.body.style.overflow = previous;
      document.removeEventListener('keydown', trap);
      media?.removeEventListener('change', onDesktop);
      previousFocus?.focus();
    };
  }, [mobileOpen]);

  const changePassword = async () => {
    setSaving(true);
    try {
      await apiPost('/auth/change-password', { currentPassword, newPassword });
      toast('Password updated', { description: 'Your other sessions were signed out.' });
      setPasswordOpen(false);
      setCurrentPassword('');
      setNewPassword('');
    } catch (error) {
      toast('Could not change password', {
        tone: 'danger',
        description: error instanceof Error ? error.message : 'Please try again.',
      });
    } finally {
      setSaving(false);
    }
  };

  const stationPicker = (mobile = false) =>
    !me?.isSuperAdmin && stations.length > 0 ? (
      <div
        className={cn(
          'relative flex min-w-0 items-center',
          mobile ? 'w-full' : 'w-[205px] lg:w-[230px]',
        )}
      >
        <Store
          className="pointer-events-none absolute left-2.5 h-3.5 w-3.5 shrink-0 text-accent"
          aria-hidden
        />
        <select
          value={station?.id ?? ''}
          onChange={(event) => selectStation(event.target.value)}
          aria-label="Active station"
          className="select-input min-h-9.5 min-w-0 w-full cursor-pointer appearance-none truncate rounded-xl border border-carbon-700/85 bg-carbon-900 py-1.5 pl-8 pr-7 text-xs font-semibold text-ink-100 shadow-sheen transition hover:border-carbon-600 focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/15"
        >
          {stations.map((item) => (
            <option key={item.id} value={item.id}>
              {item.name.replace('Sunrise Fuels — ', 'Sunrise · ')}
            </option>
          ))}
        </select>
      </div>
    ) : null;

  return (
    <div className="flex min-h-dvh bg-carbon-950">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[70] focus:rounded-xl focus:bg-accent focus:px-4 focus:py-2 focus:text-onaccent"
      >
        Skip to content
      </a>

      {/* Desktop Sidebar */}
      <aside
        className={cn(
          'sidebar-surface sticky top-0 hidden h-dvh shrink-0 flex-col border-r border-carbon-700/60 transition-[width] duration-200 ease-smooth md:flex',
          collapsed ? 'w-[74px]' : 'w-[250px] xl:w-[260px]',
        )}
      >
        <div
          className={cn(
            'flex h-[68px] shrink-0 items-center border-b border-carbon-700/65',
            collapsed ? 'justify-center px-2' : 'justify-between px-4',
          )}
        >
          <Brand collapsed={collapsed} />
          {!collapsed && (
            <button
              type="button"
              onClick={toggleCollapsed}
              title="Collapse sidebar"
              aria-label="Collapse sidebar"
              className="grid h-8 w-8 place-items-center rounded-xl text-ink-500 transition hover:bg-carbon-800 hover:text-ink-100"
            >
              <PanelLeftClose className="h-4 w-4" />
            </button>
          )}
        </div>

        <PrimaryNav collapsed={collapsed} />

        {!collapsed && !me?.isSuperAdmin && station && (
          <div className="mx-3 mb-3 rounded-2xl border border-white/10 bg-white/[0.035] p-3.5">
            <div className="flex items-center justify-between gap-2">
              <span className="inline-flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[0.13em] text-ink-500">
                <Store className="h-3 w-3 text-accent" aria-hidden />
                Selected station
              </span>
              <span className="rounded-md border border-accent/25 bg-accent/10 px-1.5 py-0.5 font-mono text-[10px] font-bold text-accent">
                {station.code}
              </span>
            </div>
            <p className="mt-1.5 truncate font-display text-xs font-bold text-ink-100">{station.name}</p>
            <p className="mt-1 flex items-center gap-1.5 text-[10.5px] text-ink-500">
              <ShieldCheck className="h-3.5 w-3.5 shrink-0 text-ok" aria-hidden />
              <span>{me?.user.roleName} workspace · IST</span>
            </p>
          </div>
        )}

        <div
          className={cn(
            'flex items-center border-t border-carbon-700/65 py-2.5 text-[10.5px] text-ink-500',
            collapsed ? 'justify-center px-2' : 'justify-between px-4',
          )}
        >
          {collapsed ? (
            <button
              type="button"
              onClick={toggleCollapsed}
              title="Expand sidebar"
              aria-label="Expand sidebar"
              className="grid h-8 w-8 place-items-center rounded-xl text-ink-400 transition hover:bg-carbon-800 hover:text-ink-100"
            >
              <PanelLeftOpen className="h-4 w-4" />
            </button>
          ) : (
            <>
              <span className="font-semibold text-ink-400">Built for your forecourt</span>
              <Fuel className="h-4 w-4 text-fuel" aria-hidden />
            </>
          )}
        </div>
      </aside>

      {/* Mobile Drawer */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <div
            className="absolute inset-0 bg-scrim/60 backdrop-blur-[3px]"
            onClick={() => setMobileOpen(false)}
          />
          <aside
            ref={drawerRef}
            role="dialog"
            aria-modal="true"
            aria-label="Navigation menu"
            className="sidebar-surface relative flex h-dvh w-[292px] max-w-[88vw] flex-col border-r border-carbon-700 shadow-pop"
          >
            <div className="flex items-center justify-between border-b border-carbon-700/70 px-4 py-4">
              <Brand />
              <button
                onClick={() => setMobileOpen(false)}
                aria-label="Close menu"
                className="grid h-9 w-9 place-items-center rounded-xl text-ink-400 hover:bg-carbon-800 hover:text-ink-100"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <div className="border-b border-carbon-700/60 px-4 py-3">{stationPicker(true)}</div>
            <PrimaryNav onNavigate={() => setMobileOpen(false)} />
            <div className="border-t border-carbon-700/70 bg-carbon-850 p-4">
              <p className="font-display text-sm font-bold text-ink-100">{me?.user.name}</p>
              <p className="mt-0.5 text-xs text-ink-500">
                {me?.user.roleName} · {me?.tenant?.name}
              </p>
              <Button
                variant="outline"
                size="sm"
                className="mt-3 w-full"
                onClick={() => void logout()}
                icon={<LogOut className="h-3.5 w-3.5" />}
              >
                Sign out
              </Button>
            </div>
          </aside>
        </div>
      )}

      {/* Main Content Shell */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-[62px] items-center gap-2 border-b border-carbon-700/65 bg-carbon-900/90 px-3 backdrop-blur-xl sm:gap-2.5 sm:px-6 md:h-[68px] lg:px-8">
          {/* Organization & Section Context */}
          <div className="min-w-0 flex-1">
            <div className="hidden items-center gap-2 text-xs md:flex">
              <span className="inline-flex min-w-0 items-center gap-1.5 rounded-lg bg-carbon-800/70 px-2 py-1 font-medium text-ink-400">
                <Building2 className="h-3.5 w-3.5 shrink-0 text-accent" aria-hidden />
                <span className="max-w-[165px] truncate lg:max-w-[225px]">
                  {me?.tenant?.name ?? 'Platform Administration'}
                </span>
              </span>
              <ChevronRight className="h-3.5 w-3.5 shrink-0 text-ink-600" aria-hidden />
              <span className="truncate font-display font-bold text-ink-100">
                {section?.label ?? 'Administration'}
              </span>
              {activeChild && activeChild.to !== section?.to && (
                <>
                  <ChevronRight className="h-3 w-3 shrink-0 text-ink-600" aria-hidden />
                  <span className="hidden truncate font-medium text-ink-400 lg:inline">{activeChild.label}</span>
                </>
              )}
              {isDemoTenant && (
                <span className="ml-1 hidden rounded-full border border-warn/30 bg-warn/10 px-2 py-0.5 text-[9.5px] font-bold tracking-wider text-warn lg:inline">
                  SANDBOX
                </span>
              )}
            </div>

            {/* Mobile Outlet Context */}
            <div className="min-w-0 md:hidden">
              {!mobileOpen && stationPicker(true) ? (
                stationPicker(true)
              ) : (
                <span className="font-display text-base font-extrabold text-ink-100">
                  Petro<span className="text-accent">Flow</span>
                </span>
              )}
            </div>
          </div>

          {/* Command Palette Trigger */}
          <button
            type="button"
            onClick={() => setCommandOpen(true)}
            aria-label="Open command palette"
            title="Quick jump & actions (⌘K)"
            className="hidden h-9.5 items-center gap-2.5 rounded-xl border border-carbon-700/85 bg-carbon-900 px-3 text-xs text-ink-400 shadow-sheen transition hover:border-accent/45 hover:bg-carbon-800/80 hover:text-ink-100 lg:inline-flex"
          >
            <Search className="h-3.5 w-3.5 text-ink-500" aria-hidden />
            <span className="font-medium">Quick jump…</span>
            <kbd className="kbd ml-1">⌘K</kbd>
          </button>

          {/* Desktop/Tablet Outlet Switcher + Business Date & Shift Status */}
          <div className="hidden items-center gap-2 md:flex">
            {stationPicker()}
            <HeaderShiftContext />
          </div>

          {/* Action Controls: Mobile Search, Notifications, Theme, Account */}
          <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
            <button
              type="button"
              onClick={() => setCommandOpen(true)}
              aria-label="Open command palette"
              className="grid h-9.5 w-9.5 shrink-0 place-items-center rounded-xl border border-carbon-700/85 bg-carbon-900 text-ink-400 shadow-sheen transition hover:border-carbon-600 hover:bg-carbon-800 hover:text-ink-100 lg:hidden"
            >
              <Search className="h-[16px] w-[16px]" aria-hidden />
            </button>
            <NotificationCenter />
            <ThemeToggle />

            <div className="relative" ref={menuRef}>
              <button
                type="button"
                onClick={() => setUserMenu((value) => !value)}
                className={cn(
                  'flex h-9.5 items-center gap-2 rounded-xl border px-1.5 shadow-sheen transition-all duration-150',
                  userMenu
                    ? 'border-accent/45 bg-accent/10'
                    : 'border-carbon-700/85 bg-carbon-900 hover:border-carbon-600 hover:bg-carbon-800',
                )}
                aria-haspopup="menu"
                aria-expanded={userMenu}
                aria-label="Account menu"
              >
                <span className="brand-gradient grid h-7 w-7 shrink-0 place-items-center rounded-lg font-display text-[11px] font-extrabold text-onbrand shadow-sm">
                  {initials(me?.user.name ?? '')}
                </span>
                <span className="hidden text-left xl:block">
                  <span className="block max-w-[125px] truncate text-xs font-semibold leading-tight text-ink-100">
                    {me?.user.name}
                  </span>
                  <span className="block text-[10px] font-medium leading-tight text-ink-500">
                    {me?.user.roleName}
                  </span>
                </span>
                <ChevronDown className="hidden h-3.5 w-3.5 text-ink-500 sm:block" aria-hidden />
              </button>

              {userMenu && (
                <div
                  role="menu"
                  className="animate-scale-in absolute right-0 top-12 z-50 w-64 overflow-hidden rounded-2xl border border-carbon-700/90 bg-carbon-850 shadow-pop"
                >
                  <div className="hero-mesh border-b border-carbon-700/75 bg-carbon-900/60 px-4 py-3.5">
                    <div className="flex items-center justify-between gap-2">
                      <p className="truncate font-display text-sm font-bold text-ink-100">{me?.user.name}</p>
                      <span className="rounded-full border border-accent/25 bg-accent/10 px-2 py-0.5 text-[10px] font-bold text-accent">
                        {me?.user.roleName}
                      </span>
                    </div>
                    <p className="mt-0.5 truncate text-xs text-ink-500">{me?.user.email}</p>
                    {me?.tenant?.name && (
                      <p className="mt-1 truncate text-[11px] font-medium text-ink-400">{me.tenant.name}</p>
                    )}
                  </div>
                  <div className="p-1.5">
                    <button
                      role="menuitem"
                      onClick={() => {
                        setPasswordOpen(true);
                        setUserMenu(false);
                      }}
                      className="menu-item"
                    >
                      <KeyRound className="h-4 w-4 text-ink-500" aria-hidden />
                      Change password
                    </button>
                    <button role="menuitem" onClick={() => void logout()} className="menu-item text-danger hover:text-danger">
                      <LogOut className="h-4 w-4" aria-hidden />
                      Sign out
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </header>

        <main
          id="main"
          className="mx-auto w-full max-w-[1600px] flex-1 px-3.5 pb-24 pt-5 sm:px-6 md:pb-8 lg:px-8 lg:pt-7"
        >
          {!me?.isSuperAdmin && <SectionNav />}
          <div key={location.pathname} className="animate-fade-in">
            <RouteErrorBoundary resetKey={location.pathname}>
              <Outlet />
            </RouteErrorBoundary>
          </div>
        </main>

        <footer className="hidden items-center justify-between gap-3 border-t border-carbon-700/65 bg-carbon-900/60 px-8 py-3 text-[11px] text-ink-500 backdrop-blur-md md:flex">
          <span>
            PetroFlow · Fuel station management
            {isDemoTenant ? ' · Demo workspace' : ''}
          </span>
          <span className="flex items-center gap-2.5">
            <span className="hidden items-center gap-1 text-ink-400 lg:inline-flex">
              <kbd className="kbd">⌘K</kbd> Quick jump
            </span>
            <span className="hidden h-3 w-px bg-carbon-700 lg:inline-block" aria-hidden />
            <span className="flex items-center gap-1.5">
              <span className="h-1.5 w-1.5 rounded-full bg-ok" aria-hidden />
              <span>INR Ledger · Asia/Kolkata (IST)</span>
            </span>
          </span>
        </footer>
      </div>

      {!me?.isSuperAdmin && <MobileNav onMenu={() => setMobileOpen(true)} />}

      <CommandPalette open={commandOpen} onClose={() => setCommandOpen(false)} />

      <Modal
        open={passwordOpen}
        onClose={() => setPasswordOpen(false)}
        title="Change password"
        description="Use at least 10 characters, including upper- and lowercase letters and a number."
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setPasswordOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={() => void changePassword()}
              loading={saving}
              disabled={newPassword.length < 10 || !currentPassword}
            >
              Update password
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Current password" required>
            <Input
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
          </Field>
          <Field label="New password" required>
            <Input
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            />
          </Field>
        </div>
      </Modal>
    </div>
  );
}
