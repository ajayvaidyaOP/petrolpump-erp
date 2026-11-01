import { useState } from 'react';
import { Download, Trash2 } from 'lucide-react';
import { apiDelete, apiGet, apiList } from '../lib/api';
import { useAuth } from '../lib/auth';
import { downloadCsv } from '../lib/utils';
import { Button, ConfirmDialog, useToast } from './ui';

export function useIsOwner() {
  const { me } = useAuth();
  return me?.user?.roleKey === 'OWNER';
}

/** Owner-only delete with confirmation. Hidden for every other role. */
export function DeleteRecordButton({
  path,
  label,
  message,
  onDeleted,
  disabled,
  size = 'sm',
}: {
  path: string;
  label: string;
  message?: string;
  onDeleted: () => void;
  disabled?: boolean;
  size?: 'sm' | 'md';
}) {
  const isOwner = useIsOwner();
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!isOwner) return null;

  const confirm = async () => {
    setBusy(true);
    try {
      await apiDelete(path);
      toast(`${label} deleted`, { tone: 'success' });
      setOpen(false);
      onDeleted();
    } catch (error) {
      toast('Could not delete', { tone: 'danger', description: (error as Error).message });
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Button
        variant="ghost"
        size={size}
        className="text-danger hover:bg-danger/10"
        disabled={disabled}
        aria-label={`Delete ${label}`}
        title={`Delete ${label}`}
        onClick={() => setOpen(true)}
      >
        <Trash2 className="h-3.5 w-3.5" />
      </Button>
      <ConfirmDialog
        open={open}
        onClose={() => setOpen(false)}
        onConfirm={() => void confirm()}
        title={`Delete ${label}?`}
        message={message ?? 'This permanently removes the record. Records used by sales, stock or accounts cannot be deleted.'}
        confirmLabel="Delete"
        danger
        loading={busy}
      />
    </>
  );
}

/** Downloads every page of a list endpoint as CSV (not just the visible page). */
export function ExportCsvButton({
  path,
  filename,
  label = 'Export CSV',
  disabled,
  arrayKey,
}: {
  path: string;
  filename: string;
  label?: string;
  disabled?: boolean;
  /** For endpoints that wrap the list in an object, e.g. { documents: [...] }. */
  arrayKey?: string;
}) {
  const { toast } = useToast();
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    try {
      const rows: Record<string, unknown>[] = [];
      if (arrayKey) {
        const obj = await apiGet<Record<string, unknown>>(path);
        const list = obj?.[arrayKey];
        if (Array.isArray(list)) rows.push(...(list as Record<string, unknown>[]));
      } else {
        const sep = path.includes('?') ? '&' : '?';
        let page = 1;
        let totalPages = 1;
        do {
          const res = await apiList<Record<string, unknown>>(`${path}${sep}page=${page}&pageSize=200`);
          rows.push(...res.data);
          totalPages = res.meta.totalPages || 1;
          page += 1;
        } while (page <= totalPages);
      }
      if (rows.length === 0) {
        toast('Nothing to export', { tone: 'warning', description: 'There are no records for the current filters.' });
        return;
      }
      const stamped = filename.endsWith('.csv') ? filename : `${filename}.csv`;
      downloadCsv(stamped, rows);
      toast(`Exported ${rows.length} row${rows.length === 1 ? '' : 's'}`, { tone: 'success', description: stamped });
    } catch (error) {
      toast('Export failed', { tone: 'danger', description: (error as Error).message });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Button variant="outline" size="sm" onClick={() => void run()} loading={busy} disabled={disabled}>
      <Download className="h-3.5 w-3.5" /> {label}
    </Button>
  );
}
