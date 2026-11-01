import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ScrollText, ShieldCheck } from 'lucide-react';
import { apiList, type ListMeta } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useStations } from '../lib/auth';
import { formatDateTime } from '../lib/format';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingBlock,
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatCard,
} from '../components/ui';

interface AuditRow {
  id: string;
  action: string;
  entity: string;
  entityId: string | null;
  stationId: string | null;
  summary: string | null;
  actorEmail: string | null;
  actorName?: string | null;
  ip: string | null;
  createdAt: string;
  beforeData: Record<string, unknown> | null;
  afterData: Record<string, unknown> | null;
}

export default function Audit() {
  const { stations, stationId } = useStations();
  const [search, setSearch] = useState('');
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<AuditRow | null>(null);

  const params = new URLSearchParams({ page: String(page), pageSize: '25' });
  if (search) params.set('search', search);
  if (action) params.set('action', action);
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  if (stationId) params.set('stationId', stationId);

  const query = useQuery<{ data: AuditRow[]; meta: ListMeta & { actions?: { action: string; count: number }[] } }>({
    queryKey: ['audit', params.toString()],
    queryFn: () => apiList<AuditRow>(`/audit?${params.toString()}`),
  });

  const rows = query.data?.data ?? [];
  const actions = query.data?.meta.actions ?? [];
  const stationMap = new Map(stations.map((s) => [s.id, s]));

  return (
    <div>
      <PageHeader
        title="Audit Log"
        description="Every sensitive change records who did it, from where, and the before/after values. Records are append-only."
        actions={<ExportCsvButton path={`/audit?${params.toString()}`} filename="petroflow-audit" />}
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="Events in view" value={query.data?.meta.total ?? 0} icon={<ScrollText className="h-4 w-4" />} />
        <StatCard label="Distinct actions" value={actions.length} icon={<ShieldCheck className="h-4 w-4" />} />
        <StatCard
          label="Station scope"
          value={stationMap.get(stationId)?.code ?? 'All'}
          hint="Audit rows are filtered to the active station."
        />
      </div>

      <Card>
        <div className="flex flex-wrap items-end gap-2 border-b border-carbon-700/60 p-3">
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Search summary, actor or entity"
            className="w-full sm:w-64"
          />
          <Field label="Action" className="w-full sm:w-52">
            <Select
              value={action}
              onChange={(e) => {
                setAction(e.target.value);
                setPage(1);
              }}
            >
              <option value="">All actions</option>
              {actions.map((a) => (
                <option key={a.action} value={a.action}>
                  {a.action} ({a.count})
                </option>
              ))}
            </Select>
          </Field>
          <Field label="From" className="w-[calc(50%-0.25rem)] sm:w-40">
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </Field>
          <Field label="To" className="w-[calc(50%-0.25rem)] sm:w-40">
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </Field>
          {(search || action || from || to) && (
            <Button
              variant="ghost"
              onClick={() => {
                setSearch('');
                setAction('');
                setFrom('');
                setTo('');
                setPage(1);
              }}
            >
              Clear
            </Button>
          )}
        </div>

        {query.isLoading ? (
          <LoadingBlock />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState title="No audit records" description="Actions you take will appear here with actor and timestamp." />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Action</th>
                    <th>Summary</th>
                    <th>Actor</th>
                    <th>Station</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="whitespace-nowrap text-xs text-ink-400">{formatDateTime(row.createdAt)}</td>
                      <td>
                        <Badge tone={row.action.includes('REVERS') || row.action.includes('DISABLED') ? 'danger' : 'neutral'}>
                          {row.action}
                        </Badge>
                      </td>
                      <td className="max-w-[360px] text-sm text-ink-200">{row.summary ?? '—'}</td>
                      <td className="text-xs text-ink-400">{row.actorEmail ?? 'system'}</td>
                      <td className="text-xs text-ink-400">
                        {row.stationId ? stationMap.get(row.stationId)?.code ?? '—' : 'Organization'}
                      </td>
                      <td className="text-right">
                        <Button size="sm" variant="ghost" onClick={() => setDetail(row)}>
                          Details
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {query.data ? (
              <Pagination
                page={query.data.meta.page}
                pageSize={query.data.meta.pageSize}
                total={query.data.meta.total}
                totalPages={query.data.meta.totalPages}
                onPage={setPage}
              />
            ) : null}
          </>
        )}
      </Card>

      <Modal
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        title={detail?.action ?? 'Audit record'}
        description={detail?.summary ?? undefined}
        size="lg"
      >
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <p className="label">Entity</p>
            <p className="text-ink-200">
              {detail?.entity} {detail?.entityId ? <span className="text-ink-500">· {detail.entityId}</span> : null}
            </p>
          </div>
          <div>
            <p className="label">Actor</p>
            <p className="text-ink-200">{detail?.actorEmail ?? 'system'}</p>
          </div>
          <div>
            <p className="label">Occurred</p>
            <p className="text-ink-200">{formatDateTime(detail?.createdAt)}</p>
          </div>
          <div>
            <p className="label">IP</p>
            <p className="text-ink-200">{detail?.ip ?? '—'}</p>
          </div>
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <div>
            <p className="label">Before</p>
            <pre className="max-h-64 overflow-auto rounded-lg border border-carbon-700/60 bg-inset p-3 text-[11px] text-ink-300">
              {JSON.stringify(detail?.beforeData ?? null, null, 2)}
            </pre>
          </div>
          <div>
            <p className="label">After</p>
            <pre className="max-h-64 overflow-auto rounded-lg border border-carbon-700/60 bg-inset p-3 text-[11px] text-ink-300">
              {JSON.stringify(detail?.afterData ?? null, null, 2)}
            </pre>
          </div>
        </div>
      </Modal>
    </div>
  );
}
