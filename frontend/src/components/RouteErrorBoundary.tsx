import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { RotateCw, ShieldAlert } from 'lucide-react';
import { Button } from './ui';

/**
 * Isolates a single screen's render failure so the sidebar, station switcher and
 * navigation keep working. Remounts (via `resetKey`) whenever the route changes,
 * so navigating away always clears the error.
 */
export class RouteErrorBoundary extends Component<
  { children: ReactNode; resetKey: string },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidUpdate(prev: { resetKey: string }) {
    if (prev.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('PetroFlow screen failed', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="card mx-auto max-w-xl p-6">
        <div className="flex items-start gap-3 rounded-2xl border border-danger/25 bg-danger/8 p-4">
          <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl border border-danger/25 bg-danger/12 text-danger">
            <ShieldAlert className="h-4 w-4" aria-hidden />
          </span>
          <div className="min-w-0">
            <h1 className="font-display text-[16px] font-bold text-ink-100">This screen hit a problem</h1>
            <p className="mt-1 text-xs leading-relaxed text-ink-400">
              Your saved data is safe. The rest of PetroFlow still works — use the menu to continue, or retry this
              screen.
            </p>
            <p className="mt-2 break-words rounded-lg bg-carbon-900/70 px-2.5 py-1.5 font-mono text-[11px] text-ink-500">
              {this.state.error.message || 'Unexpected render error'}
            </p>
          </div>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="primary" icon={<RotateCw className="h-4 w-4" />} onClick={() => this.setState({ error: null })}>
            Try again
          </Button>
          <Link to="/">
            <Button variant="outline">Back to overview</Button>
          </Link>
        </div>
      </div>
    );
  }
}
