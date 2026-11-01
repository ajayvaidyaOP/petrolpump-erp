import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { createPortal } from 'react-dom';
import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  Fuel,
  Info,
  Loader2,
  RotateCw,
  Search,
  X,
  XCircle,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { uuid } from '../lib/browser';

/* ------------------------------------------------------------------ button */
type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'success' | 'outline';
type ButtonSize = 'sm' | 'md' | 'lg';

const buttonVariants: Record<ButtonVariant, string> = {
  primary:
    'brand-gradient text-onbrand font-semibold shadow-glow border border-fuel/40 hover:brightness-[1.06] active:scale-[.985]',
  secondary:
    'bg-carbon-800/90 text-ink-100 font-semibold border border-carbon-600/75 shadow-sheen hover:bg-carbon-750/80 hover:border-accent/45 active:scale-[.985]',
  ghost:
    'text-ink-300 font-medium hover:bg-carbon-800/85 hover:text-ink-100 border border-transparent',
  danger:
    'bg-danger text-ondanger font-semibold border border-danger shadow-[inset_0_1px_0_rgb(255_255_255/0.2)] hover:brightness-110 active:scale-[.985]',
  success:
    'bg-ok text-onok font-semibold border border-ok shadow-[inset_0_1px_0_rgb(255_255_255/0.2)] hover:brightness-110 active:scale-[.985]',
  outline:
    'bg-carbon-900/80 border border-carbon-600/80 text-ink-200 font-semibold shadow-sheen hover:bg-carbon-800/70 hover:border-accent/55 hover:text-ink-100 active:scale-[.985]',
};

const buttonSizes: Record<ButtonSize, string> = {
  sm: 'h-8.5 min-h-[34px] px-3 text-xs gap-1.5',
  md: 'h-10 px-4 text-sm gap-2',
  lg: 'h-11 px-5 text-sm gap-2',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  icon?: ReactNode;
}

export function Button({
  variant = 'secondary',
  size = 'md',
  loading,
  icon,
  className,
  children,
  disabled,
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      disabled={disabled || loading}
      className={cn(
        'inline-flex select-none items-center justify-center whitespace-nowrap rounded-xl ring-offset-0 transition-all duration-150 ease-smooth',
        'btn-touch disabled:cursor-not-allowed disabled:opacity-50',
        buttonVariants[variant],
        buttonSizes[size],
        className,
      )}
    >
      {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  );
}

/* -------------------------------------------------------------------- card */
export function Card({ className, children, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={cn('card', className)} {...props}>
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  subtitle,
  action,
  className,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex flex-wrap items-center justify-between gap-3 border-b border-carbon-700/60 px-4 py-3.5 sm:px-5 sm:py-4',
        className,
      )}
    >
      <div className="min-w-0">
        <h2 className="font-display text-[15px] font-bold tracking-[-0.015em] text-ink-100">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs leading-relaxed text-ink-400">{subtitle}</p>}
      </div>
      {action && <div className="flex min-w-0 flex-wrap items-center gap-2 max-sm:w-full">{action}</div>}
    </div>
  );
}

export function CardBody({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('p-4 sm:p-5', className)}>{children}</div>;
}

/* ------------------------------------------------------------------ badges */
export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info' | 'muted';

const toneClasses: Record<BadgeTone, string> = {
  neutral: 'bg-carbon-800/90 text-ink-200 border-carbon-600/70',
  accent: 'bg-accent/12 text-accent border-accent/25',
  success: 'bg-ok/12 text-ok border-ok/25',
  warning: 'bg-warn/12 text-warn border-warn/25',
  danger: 'bg-danger/12 text-danger border-danger/25',
  info: 'bg-info/12 text-info border-info/25',
  muted: 'bg-carbon-800/65 text-ink-500 border-carbon-700/80',
};

export function Badge({
  tone = 'neutral',
  children,
  className,
  dot,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
  dot?: boolean;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[11px] font-semibold tracking-tight',
        toneClasses[tone],
        className,
      )}
    >
      {dot && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" aria-hidden />}
      {children}
    </span>
  );
}

const statusTone: Record<string, BadgeTone> = {
  ACTIVE: 'success',
  ONLINE: 'success',
  VALID: 'success',
  OK: 'success',
  WITHIN_NORM: 'success',
  CLOSED: 'success',
  BALANCED: 'success',
  PAID: 'success',
  APPROVED: 'success',
  SETTLED: 'success',
  PROCESSED: 'success',
  CAPTURED: 'success',
  RECEIVED: 'success',
  POSTED: 'info',
  OPEN: 'info',
  REOPENED: 'info',
  TRIALING: 'info',
  ISSUED: 'info',
  PENDING: 'warning',
  FLAGGED: 'warning',
  VARIANCE: 'warning',
  PAST_DUE: 'warning',
  OVERDUE: 'warning',
  EXPIRING: 'warning',
  GAIN_CHECK: 'warning',
  SUSPICIOUS: 'warning',
  STALE: 'warning',
  GAP: 'warning',
  MAINTENANCE: 'warning',
  SIMULATED: 'warning',
  DRAFT: 'muted',
  CLOSED_EMPTY: 'muted',
  INACTIVE: 'muted',
  NO_DIP: 'muted',
  OFFLINE: 'danger',
  EXPIRED: 'danger',
  BEYOND_NORM: 'danger',
  SUSPENDED: 'danger',
  DISABLED: 'danger',
  REJECTED: 'danger',
  ERROR: 'danger',
  NEGATIVE: 'danger',
  RESET: 'danger',
  DISPUTED: 'danger',
  REVERSED: 'danger',
  CANCELLED: 'danger',
  BLOCKED: 'danger',
};

export function StatusBadge({ status, label }: { status: string | null | undefined; label?: string }) {
  if (!status) return <Badge tone="muted">—</Badge>;
  return (
    <Badge tone={statusTone[status] ?? 'neutral'} dot>
      {label ?? status.replace(/_/g, ' ')}
    </Badge>
  );
}

/* ------------------------------------------------------------------- forms */
export function Field({
  label,
  error,
  hint,
  children,
  required,
  className,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: ReactNode;
  required?: boolean;
  className?: string;
}) {
  return (
    <label className={cn('block', className)}>
      {label && (
        <span className="label">
          {label} {required && <span className="text-danger">*</span>}
        </span>
      )}
      {children}
      {hint && !error && <span className="mt-1.5 block text-[11px] leading-snug text-ink-500">{hint}</span>}
      {error && <span className="mt-1.5 block text-xs font-medium text-danger">{error}</span>}
    </label>
  );
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn('input', className)} {...props} />;
}

export function Select({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn('input select-input appearance-none bg-carbon-900', className)} {...props}>
      {children}
    </select>
  );
}

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn('input min-h-[82px] resize-y', className)} {...props} />;
}

export function Checkbox({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: ReactNode;
  disabled?: boolean;
}) {
  return (
    <label className={cn('flex cursor-pointer items-center gap-2.5 text-sm text-ink-200', disabled && 'opacity-60')}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="h-4 w-4 rounded border-carbon-600 bg-carbon-900 accent-accent focus:ring-accent/40"
      />
      <span>{label}</span>
    </label>
  );
}

export function SearchInput({
  value,
  onChange,
  placeholder = 'Search…',
  className,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
}) {
  return (
    <div className={cn('relative', className)}>
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500" aria-hidden />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="input pl-9"
        aria-label={placeholder}
      />
    </div>
  );
}

/* ------------------------------------------------------------------- meter */
export function MeterBar({
  value,
  tone = 'accent',
  color,
  height = 'md',
  label,
  className,
}: {
  value: number;
  tone?: 'accent' | 'ok' | 'warn' | 'danger' | 'info';
  color?: string;
  height?: 'sm' | 'md' | 'lg';
  label?: string;
  className?: string;
}) {
  const clamped = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
  const fillClasses = {
    accent: 'brand-gradient',
    ok: 'bg-ok',
    warn: 'bg-warn',
    danger: 'bg-danger',
    info: 'bg-info',
  };
  const heights = { sm: 'h-1.5', md: 'h-2', lg: 'h-2.5' };
  return (
    <div
      role={label ? 'meter' : undefined}
      aria-label={label}
      aria-valuenow={label ? Math.round(clamped) : undefined}
      aria-valuemin={label ? 0 : undefined}
      aria-valuemax={label ? 100 : undefined}
      className={cn(
        'w-full overflow-hidden rounded-full bg-carbon-800 ring-1 ring-inset ring-carbon-700/65',
        heights[height],
        className,
      )}
    >
      <div
        className={cn(
          'relative h-full rounded-full shadow-[inset_0_1px_0_rgb(255_255_255/0.25)] transition-all duration-500 ease-smooth',
          !color && fillClasses[tone],
        )}
        style={{ width: `${clamped}%`, ...(color ? { backgroundColor: color } : {}) }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ states */
export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn('h-5 w-5 animate-spin text-accent', className)} aria-hidden />;
}

/** Full-page splash used while a lazily-loaded screen or the session resolves. */
export function PageLoader({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="grid min-h-dvh place-items-center bg-carbon-950 px-6">
      <div className="flex flex-col items-center gap-4">
        <div className="brand-gradient relative grid h-12 w-12 place-items-center overflow-hidden rounded-2xl text-onbrand shadow-glow ring-1 ring-accent/30">
          <span aria-hidden className="absolute inset-0 bg-[linear-gradient(145deg,rgb(255_255_255/0.26),transparent_55%)]" />
          <Fuel className="relative h-5 w-5" strokeWidth={2.3} />
        </div>
        <div className="flex items-center gap-2 rounded-full border border-carbon-700/75 bg-carbon-900/80 px-3.5 py-1.5 shadow-card">
          <Spinner className="h-3.5 w-3.5" />
          <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-ink-400">{label}</p>
        </div>
      </div>
    </div>
  );
}

export function LoadingBlock({ label = 'Loading…', rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div role="status" aria-live="polite" className="space-y-2.5 p-4 sm:p-5">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="skeleton h-11" style={{ animationDelay: `${i * 80}ms` }} />
      ))}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message =
    error instanceof Error ? error.message : typeof error === 'string' ? error : 'Something went wrong';
  return (
    <div className="flex flex-col items-start gap-3.5 rounded-2xl border border-danger/30 bg-danger/8 p-4 sm:p-5">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl border border-danger/25 bg-danger/12 text-danger">
          <AlertTriangle className="h-4 w-4" />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-danger">{message}</p>
          <p className="mt-0.5 text-xs leading-relaxed text-ink-400">
            Nothing was changed. Retry, or check your connection if this keeps happening.
          </p>
        </div>
      </div>
      {onRetry && (
        <Button size="sm" variant="outline" onClick={onRetry} icon={<RotateCw className="h-3.5 w-3.5" />}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-12 text-center">
      <div className="relative mb-2 grid h-12 w-12 place-items-center rounded-2xl border border-accent/20 bg-accent/10 text-accent shadow-sheen">
        {icon ?? <Info className="h-5 w-5" />}
      </div>
      <p className="font-display text-[15px] font-bold tracking-tight text-ink-100">{title}</p>
      {description && <p className="max-w-md text-xs leading-relaxed text-ink-400">{description}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}

/* ------------------------------------------------------------------- modal */
// A stack keeps nested dialogs accessible: only the top dialog handles Escape,
// and the page scroll lock is released only after the last dialog closes.
const modalStack: symbol[] = [];
let bodyOverflowBeforeModal = '';

export function Modal({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = 'md',
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const id = Symbol('modal');
    const previousFocus = document.activeElement as HTMLElement | null;
    if (modalStack.length === 0) {
      bodyOverflowBeforeModal = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
    }
    modalStack.push(id);
    const focusable = () =>
      Array.from(
        ref.current?.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ) ?? [],
      ).filter((element) => element.getClientRects().length > 0);
    const timer = window.setTimeout(() => {
      const firstField = focusable().find((element) => !element.hasAttribute('data-close'));
      (firstField ?? ref.current)?.focus();
    }, 50);
    const onKey = (event: KeyboardEvent) => {
      if (modalStack[modalStack.length - 1] !== id) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;
      const items = focusable();
      if (items.length === 0) {
        event.preventDefault();
        ref.current?.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (!ref.current?.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener('keydown', onKey);
      const index = modalStack.indexOf(id);
      if (index >= 0) modalStack.splice(index, 1);
      if (modalStack.length === 0) document.body.style.overflow = bodyOverflowBeforeModal;
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [open]);

  if (!open) return null;
  const widths = { sm: 'max-w-md', md: 'max-w-lg', lg: 'max-w-2xl', xl: 'max-w-4xl' };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center overflow-y-auto overscroll-contain bg-scrim/60 p-0 backdrop-blur-[3px] sm:items-center sm:p-6">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        className={cn(
          'animate-scale-in my-auto flex max-h-[92dvh] w-full flex-col rounded-t-3xl border border-carbon-700/85 bg-carbon-850 shadow-pop ring-1 ring-white/5 outline-none sm:max-h-[88dvh] sm:rounded-2.5xl',
          widths[size],
        )}
      >
        <div className="hero-mesh flex shrink-0 items-start justify-between gap-4 border-b border-carbon-700/65 bg-carbon-900/60 px-4 py-4 sm:rounded-t-2.5xl sm:px-6">
          <div className="min-w-0">
            <h2 id={titleId} className="font-display text-[17px] font-extrabold tracking-[-0.02em] text-ink-100">
              {title}
            </h2>
            {description && (
              <p id={descriptionId} className="mt-0.5 text-xs leading-relaxed text-ink-400">
                {description}
              </p>
            )}
          </div>
          <button
            type="button"
            data-close
            onClick={onClose}
            className="btn-touch -mr-1 grid h-8 w-8 shrink-0 place-items-center rounded-xl border border-transparent text-ink-400 transition hover:border-carbon-700 hover:bg-carbon-800 hover:text-ink-100"
            aria-label="Close dialog"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4 scroll-thin sm:px-6 sm:py-5">{children}</div>
        {footer && (
          <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-carbon-700/65 bg-carbon-800/35 px-4 py-3.5 pb-[max(0.875rem,env(safe-area-inset-bottom))] sm:rounded-b-2.5xl sm:px-6 sm:pb-3.5">
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel = 'Confirm',
  danger,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  loading?: boolean;
}) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p className="text-sm leading-relaxed text-ink-300">{message}</p>
    </Modal>
  );
}

/* ------------------------------------------------------------------ toasts */
interface Toast {
  id: string;
  title: string;
  description?: string;
  tone: 'success' | 'danger' | 'info' | 'warning';
}

interface ToastContextValue {
  toast: (title: string, options?: { description?: string; tone?: Toast['tone'] }) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (title: string, options?: { description?: string; tone?: Toast['tone'] }) => {
      const id = uuid();
      setToasts((current) => [
        ...current.slice(-3),
        { id, title, description: options?.description, tone: options?.tone ?? 'success' },
      ]);
      setTimeout(() => dismiss(id), 5200);
    },
    [dismiss],
  );

  const value = useMemo(() => ({ toast }), [toast]);
  const icons = {
    success: (
      <span className="grid h-7 w-7 place-items-center rounded-lg border border-ok/25 bg-ok/12 text-ok">
        <Check className="h-3.5 w-3.5" />
      </span>
    ),
    danger: (
      <span className="grid h-7 w-7 place-items-center rounded-lg border border-danger/25 bg-danger/12 text-danger">
        <XCircle className="h-3.5 w-3.5" />
      </span>
    ),
    warning: (
      <span className="grid h-7 w-7 place-items-center rounded-lg border border-warn/25 bg-warn/12 text-warn">
        <AlertTriangle className="h-3.5 w-3.5" />
      </span>
    ),
    info: (
      <span className="grid h-7 w-7 place-items-center rounded-lg border border-info/25 bg-info/12 text-info">
        <Info className="h-3.5 w-3.5" />
      </span>
    ),
  };

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed bottom-[calc(5rem+env(safe-area-inset-bottom))] right-4 z-[60] flex w-[calc(100vw-2rem)] max-w-sm flex-col gap-2.5 md:bottom-5"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className="animate-slide-in pointer-events-auto relative flex items-start gap-3 overflow-hidden rounded-2xl border border-carbon-600/75 bg-carbon-850/95 p-3.5 pl-4 shadow-pop backdrop-blur-xl"
          >
            <span
              aria-hidden
              className={cn(
                'absolute inset-y-0 left-0 w-[3px]',
                t.tone === 'success' && 'bg-ok',
                t.tone === 'danger' && 'bg-danger',
                t.tone === 'warning' && 'bg-warn',
                t.tone === 'info' && 'bg-info',
              )}
            />
            <div className="shrink-0">{icons[t.tone]}</div>
            <div className="min-w-0 flex-1 pt-0.5">
              <p className="text-sm font-semibold text-ink-100">{t.title}</p>
              {t.description && <p className="mt-0.5 text-xs leading-snug text-ink-400">{t.description}</p>}
            </div>
            <button
              onClick={() => dismiss(t.id)}
              className="rounded-lg p-1 text-ink-500 transition hover:bg-carbon-800 hover:text-ink-200"
              aria-label="Dismiss"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside ToastProvider');
  return ctx;
}

/* ------------------------------------------------------------- pagination */
export function Pagination({
  page,
  pageSize,
  total,
  totalPages,
  onPage,
}: {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to = Math.min(total, page * pageSize);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-carbon-700/70 bg-carbon-800/35 px-4 py-3 sm:px-5">
      <p className="num text-xs text-ink-500">
        Showing <span className="font-semibold text-ink-200">{from}</span>–
        <span className="font-semibold text-ink-200">{to}</span> of{' '}
        <span className="font-semibold text-ink-200">{total}</span>
      </p>
      <div className="flex items-center gap-1.5">
        <Button
          size="sm"
          variant="outline"
          onClick={() => onPage(page - 1)}
          disabled={page <= 1}
          aria-label="Previous page"
          className="px-2"
        >
          <ChevronLeft className="h-4 w-4" />
        </Button>
        <span className="num rounded-lg border border-carbon-700/80 bg-carbon-900 px-2.5 py-1 text-xs font-semibold text-ink-300">
          Page {page} / {totalPages}
        </span>
        <Button
          size="sm"
          variant="outline"
          onClick={() => onPage(page + 1)}
          disabled={page >= totalPages}
          aria-label="Next page"
          className="px-2"
        >
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ layout */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumb?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4 border-b border-carbon-700/65 pb-4">
      <div className="min-w-0">
        {breadcrumb && (
          <div className="mb-1.5 inline-flex items-center gap-1.5 rounded-full border border-accent/20 bg-accent/10 px-2.5 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.12em] text-accent">
            {breadcrumb}
          </div>
        )}
        <h1 className="font-display text-[25px] font-extrabold leading-8 tracking-[-0.03em] text-ink-100 sm:text-[28px] sm:leading-9">
          {title}
        </h1>
        {description && <p className="mt-1 max-w-3xl text-xs leading-relaxed text-ink-400 sm:text-[13.5px]">{description}</p>}
      </div>
      {actions && <div className="flex w-full flex-wrap items-end gap-2 sm:w-auto">{actions}</div>}
    </div>
  );
}

export function StatCard({
  label,
  value,
  hint,
  icon,
  tone = 'default',
  footer,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  icon?: ReactNode;
  tone?: 'default' | 'accent' | 'success' | 'warning' | 'danger';
  footer?: ReactNode;
}) {
  const iconTones = {
    default: 'border border-carbon-700 bg-carbon-800 text-ink-300',
    accent: 'border border-accent/20 bg-accent/12 text-accent',
    success: 'border border-ok/20 bg-ok/12 text-ok',
    warning: 'border border-warn/20 bg-warn/12 text-warn',
    danger: 'border border-danger/20 bg-danger/12 text-danger',
  };
  const surfaces = {
    default: '',
    accent: 'border-accent/20 bg-gradient-to-br from-accent/[0.09] via-carbon-850 to-carbon-850',
    success: 'border-ok/20 bg-gradient-to-br from-ok/[0.08] via-carbon-850 to-carbon-850',
    warning: 'border-warn/20 bg-gradient-to-br from-warn/[0.09] via-carbon-850 to-carbon-850',
    danger: 'border-danger/20 bg-gradient-to-br from-danger/[0.08] via-carbon-850 to-carbon-850',
  };
  return (
    <div data-tone={tone} className={cn('card stat-card relative h-full overflow-hidden p-4 sm:p-5', surfaces[tone])}>
      <div className="relative flex items-start justify-between gap-3">
        <p className="pt-1 text-[11px] font-bold uppercase leading-snug tracking-[0.07em] text-ink-400">{label}</p>
        {icon && (
          <div className={cn('grid h-9 w-9 shrink-0 place-items-center rounded-xl', iconTones[tone])}>
            {icon}
          </div>
        )}
      </div>
      <p className="num relative mt-3 break-words font-display text-[21px] font-extrabold leading-tight tracking-[-0.035em] text-ink-100 sm:text-[27px]">
        {value}
      </p>
      {hint && <p className="mt-2 text-[11.5px] leading-relaxed text-ink-500">{hint}</p>}
      {footer && <div className="mt-3 border-t border-carbon-700/60 pt-2.5 text-xs text-ink-500">{footer}</div>}
    </div>
  );
}

export function Tabs({
  tabs,
  active,
  onChange,
}: {
  tabs: { key: string; label: string; count?: number }[];
  active: string;
  onChange: (key: string) => void;
}) {
  return (
    <div
      role="tablist"
      className="inline-flex max-w-full gap-1 overflow-x-auto rounded-2xl border border-carbon-700/80 bg-carbon-800/75 p-1 shadow-sheen scroll-thin"
    >
      {tabs.map((tab) => {
        const isSelected = active === tab.key;
        return (
          <button
            key={tab.key}
            role="tab"
            type="button"
            aria-selected={isSelected}
            onClick={() => onChange(tab.key)}
            className={cn(
              'whitespace-nowrap rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all duration-150 sm:text-[13px]',
              isSelected
                ? 'border border-accent/25 bg-carbon-900 text-accent shadow-[var(--shadow-card),var(--card-sheen)]'
                : 'border border-transparent text-ink-400 hover:bg-carbon-750/60 hover:text-ink-100',
            )}
          >
            {tab.label}
            {typeof tab.count === 'number' && (
              <span
                className={cn(
                  'num ml-1.5 rounded-md px-1.5 py-0.5 text-[10px] font-bold',
                  isSelected ? 'bg-accent/14 text-accent' : 'bg-carbon-750 text-ink-400',
                )}
              >
                {tab.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

export function TableScrollHint({ className }: { className?: string }) {
  return (
    <p
      className={cn(
        'flex items-center justify-between gap-2 border-b border-carbon-700/70 bg-carbon-800/45 px-4 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-ink-500 sm:hidden',
        className,
      )}
    >
      <span>Swipe sideways for all columns</span>
      <span aria-hidden>↔</span>
    </p>
  );
}

export function DemoNotice({ children }: { children?: ReactNode }) {
  return (
    <div className="callout-warn mb-4 flex items-start gap-2.5">
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <span className="leading-relaxed">
        {children ??
          'DEMO DATA — seeded demonstration records. Clearly separated from real operational data and never created in production.'}
      </span>
    </div>
  );
}

export function KeyValue({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-3.5 sm:grid-cols-2">
      {items.map((item) => (
        <div key={item.label} className="border-b border-carbon-700/45 pb-2.5 last:border-b-0 sm:border-b-0 sm:pb-0">
          <dt className="text-[11px] font-semibold uppercase tracking-[0.06em] text-ink-500">{item.label}</dt>
          <dd className="mt-0.5 text-sm font-medium text-ink-100">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
