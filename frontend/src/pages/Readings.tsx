import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Cpu, FlaskConical, PencilLine, RefreshCw, Radio } from 'lucide-react';
import { apiGet, apiList, apiPost, type ListMeta } from '../lib/api';
import { DeleteRecordButton, ExportCsvButton } from '../components/OwnerActions';
import { useAuth, useStations } from '../lib/auth';
import { formatDateTime, formatLitres, formatNumber, timeAgo } from '../lib/format';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  DemoNotice,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingBlock,
  Modal,
  PageHeader,
  Pagination,
  Select,
  StatCard,
  StatusBadge,
  Textarea,
  useToast,
} from '../components/ui';

interface Reading {
  id: string;
  nozzleId: string;
  nozzleLabel: string;
  nozzleNumber: number;
  dispenserCode: string;
  dispenserName: string;
  productName: string;
  readingValue: string;
  unit: string;
  deltaLitres: string | null;
  quality: string;
  source: string;
  readingAt: string;
  sequence: number;
  isSimulated: number;
  deviceName: string | null;
  note: string | null;
}

interface Nozzle {
  id: string;
  label: string;
  number: number;
  dispenserId: string;
  productId: string;
}

interface Diagnostics {
  devices: { id: string; name: string; online: boolean }[];
  eventCounts: Record<string, number>;
  qualityCounts: Record<string, number>;
  lastEventAt: string | null;
  realHardwareVerified: boolean;
  disclaimer: string;
}

const qualities = ['VALID', 'SUSPICIOUS', 'NEGATIVE', 'STALE', 'RESET', 'GAP'];

export default function Readings() {
  const { can, me } = useAuth();
  const { stationId, station } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [nozzleId, setNozzleId] = useState('');
  const [quality, setQuality] = useState('');
  const [source, setSource] = useState('');
  const [page, setPage] = useState(1);
  const [manualOpen, setManualOpen] = useState(false);
  const [manualNozzle, setManualNozzle] = useState('');
  const [manualValue, setManualValue] = useState('');
  const [manualNote, setManualNote] = useState('');

  const nozzles = useQuery({
    queryKey: ['nozzles', stationId],
    queryFn: () => apiGet<Nozzle[]>(`/catalog/nozzles?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });

  const diagnostics = useQuery({
    queryKey: ['diagnostics', stationId],
    queryFn: () => apiGet<Diagnostics>(`/hardware/diagnostics?stationId=${stationId}`),
    enabled: Boolean(stationId) && can('devices.read'),
    refetchInterval: 20_000,
  });

  const list = useQuery({
    queryKey: ['readings', stationId, nozzleId, quality, source, page],
    queryFn: () =>
      apiList<Reading>(
        `/readings?stationId=${stationId}&page=${page}&pageSize=20${nozzleId ? `&nozzleId=${nozzleId}` : ''}${quality ? `&quality=${quality}` : ''}${source ? `&source=${source}` : ''}`,
      ),
    enabled: Boolean(stationId),
    refetchInterval: 15_000,
  });

  const simulate = useMutation({
    mutationFn: () => apiPost<{ generated: { eventId: string }[] }>('/demo/simulate', { stationId, events: 5 }),
    onSuccess: (result) => {
      toast('Simulated readings ingested', {
        description: `${result.generated.length} generated · marked SIMULATED`,
      });
      void queryClient.invalidateQueries({ queryKey: ['readings'] });
      void queryClient.invalidateQueries({ queryKey: ['diagnostics'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
    onError: (error) => toast('Simulation failed', { tone: 'danger', description: (error as Error).message }),
  });

  const manual = useMutation({
    mutationFn: () =>
      apiPost('/readings/manual', {
        nozzleId: manualNozzle,
        readingValue: manualValue,
        unit: 'L',
        note: manualNote || undefined,
      }),
    onSuccess: () => {
      toast('Manual reading recorded', { description: 'Marked MANUAL with an audit trail.' });
      setManualOpen(false);
      setManualValue('');
      setManualNote('');
      void queryClient.invalidateQueries({ queryKey: ['readings'] });
      void queryClient.invalidateQueries({ queryKey: ['diagnostics'] });
    },
    onError: (error) => toast('Could not record reading', { tone: 'danger', description: (error as Error).message }),
  });

  if (!stationId) return <EmptyState title="No station assigned" />;

  const rows = list.data?.data ?? [];
  const meta: ListMeta = list.data?.meta ?? { page: 1, pageSize: 20, total: 0, totalPages: 1 };
  const qualityCounts = diagnostics.data?.qualityCounts ?? {};
  const invalid = (qualityCounts.SUSPICIOUS ?? 0) + (qualityCounts.NEGATIVE ?? 0) + (qualityCounts.STALE ?? 0) + (qualityCounts.GAP ?? 0) + (qualityCounts.RESET ?? 0);

  return (
    <>
      <PageHeader
        title="Live hardware readings"
        description={`${station?.name ?? ''} · raw device snapshots and validated meter readings with quality status`}
        actions={
          <>
            <ExportCsvButton path={`/readings?stationId=${stationId}`} filename="petroflow-readings" />
            <Button size="sm" variant="ghost" icon={<RefreshCw className="h-3.5 w-3.5" />} onClick={() => { void list.refetch(); if (can('devices.read')) void diagnostics.refetch(); }}>
              Refresh
            </Button>
            {can('readings.manual') && (
              <Button
                size="sm"
                variant="secondary"
                icon={<PencilLine className="h-4 w-4" />}
                onClick={() => {
                  setManualNozzle(nozzles.data?.[0]?.id ?? '');
                  setManualOpen(true);
                }}
              >
                Manual entry
              </Button>
            )}
            {me?.demoMode && can('devices.write') && (
              <Button size="sm" variant="primary" icon={<FlaskConical className="h-4 w-4" />} onClick={() => simulate.mutate()} loading={simulate.isPending}>
                Simulate readings
              </Button>
            )}
          </>
        }
      />

      <DemoNotice>
        SIMULATED hardware — readings from the demo gateway are marked SIMULATED. A physical dispenser/controller
        integration remains unverified until tested with documented vendor protocols.
      </DemoNotice>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Readings stored" value={list.data?.meta.total ?? 0} hint="Validated meter readings" icon={<Activity className="h-4 w-4" />} />
        {can('devices.read') && (
          <>
            <StatCard label="Quality exceptions" value={diagnostics.data ? invalid : '—'} hint="Non-countable or flagged snapshots" icon={<Cpu className="h-4 w-4" />} tone={invalid > 0 ? 'warning' : 'success'} />
            <StatCard
              label="Gateways online"
              value={diagnostics.data ? `${diagnostics.data.devices.filter((d) => d.online).length}/${diagnostics.data.devices.length}` : '—'}
              hint={`Last event ${timeAgo(diagnostics.data?.lastEventAt)}`}
              icon={<Radio className="h-4 w-4" />}
            />
            <StatCard
              label="Raw events"
              value={diagnostics.data ? Object.values(diagnostics.data.eventCounts).reduce((a, b) => a + b, 0) : '—'}
              hint={Object.entries(diagnostics.data?.eventCounts ?? {}).map(([k, v]) => `${k} ${v}`).join(' · ') || 'No events yet'}
              icon={<Cpu className="h-4 w-4" />}
            />
          </>
        )}
      </div>

      <Card className="mt-3">
        <CardHeader
          title="Reading history"
          subtitle="Cumulative snapshots with derived deltas. Each event is stored once; retries are deduplicated by device event id."
          action={
            <div className="flex flex-wrap gap-2 max-sm:w-full">
              <Select value={nozzleId} onChange={(e) => { setNozzleId(e.target.value); setPage(1); }} className="h-9 w-[calc(50%-0.25rem)] py-1 text-xs sm:h-8 sm:w-40">
                <option value="">All nozzles</option>
                {(nozzles.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
              </Select>
              <Select value={quality} onChange={(e) => { setQuality(e.target.value); setPage(1); }} className="h-9 w-[calc(50%-0.25rem)] py-1 text-xs sm:h-8 sm:w-36">
                <option value="">All quality</option>
                {qualities.map((q) => <option key={q} value={q}>{q}</option>)}
              </Select>
              <Select value={source} onChange={(e) => { setSource(e.target.value); setPage(1); }} className="h-9 w-full py-1 text-xs sm:h-8 sm:w-32">
                <option value="">All sources</option>
                <option value="SIMULATOR">SIMULATOR</option>
                <option value="DEVICE">DEVICE</option>
                <option value="MANUAL">MANUAL</option>
                <option value="GATEWAY_REPLAY">REPLAY</option>
              </Select>
            </div>
          }
        />
        {list.isLoading ? (
          <LoadingBlock rows={5} />
        ) : list.isError ? (
          <div className="p-4"><ErrorState error={list.error} onRetry={() => void list.refetch()} /></div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No readings match the filters"
            description="Use the simulator to generate test events or record a manual fallback reading."
            action={me?.demoMode && can('devices.write') ? <Button size="sm" variant="primary" onClick={() => simulate.mutate()}>Simulate readings</Button> : undefined}
          />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Nozzle</th>
                    <th className="text-right">Totalizer</th>
                    <th className="text-right">Delta</th>
                    <th>Quality</th>
                    <th>Source</th>
                    <th>Read at</th>
                    <th>Sequence</th>
                    <th>Device</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((reading) => (
                    <tr key={reading.id}>
                      <td>
                        <p className="font-medium text-ink-100">{reading.nozzleLabel}</p>
                        <p className="text-[10px] text-ink-500">{reading.dispenserCode} · {reading.productName}</p>
                      </td>
                      <td className="num text-right">
                        {formatNumber(reading.readingValue, 3)} <span className="text-[10px] text-ink-500">{reading.unit}</span>
                      </td>
                      <td className={`num text-right ${reading.deltaLitres && Number(reading.deltaLitres) < 0 ? 'text-danger' : 'text-ink-300'}`}>
                        {reading.deltaLitres ? formatLitres(reading.deltaLitres) : '—'}
                      </td>
                      <td>
                        <StatusBadge status={reading.quality} />
                        {reading.note && <p className="mt-1 max-w-[260px] text-[10px] leading-snug text-ink-500">{reading.note}</p>}
                      </td>
                      <td>
                        <Badge tone={reading.source === 'SIMULATOR' ? 'warning' : reading.source === 'MANUAL' ? 'info' : 'neutral'}>
                          {reading.source}
                        </Badge>
                      </td>
                      <td className="text-xs text-ink-400">{formatDateTime(reading.readingAt)}</td>
                      <td className="num text-xs text-ink-500">{reading.sequence}</td>
                      <td className="text-xs text-ink-500">
                        <span className="inline-flex items-center gap-1">
                          {reading.deviceName ?? '—'}
                          {reading.source === 'MANUAL' && (
                            <DeleteRecordButton path={`/readings/${reading.id}`} label="reading" onDeleted={() => void list.refetch()} />
                          )}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination {...meta} onPage={setPage} />
          </>
        )}
      </Card>

      <Modal
        open={manualOpen}
        onClose={() => setManualOpen(false)}
        title="Manual totalizer entry"
        description="Authorized fallback when the gateway is offline. The reading is classified against the previous snapshot."
        footer={
          <>
            <Button variant="ghost" onClick={() => setManualOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={manual.isPending} disabled={!manualNozzle || !manualValue} onClick={() => manual.mutate()}>
              Record reading
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Field label="Nozzle" required>
            <Select value={manualNozzle} onChange={(e) => setManualNozzle(e.target.value)}>
              <option value="">Select nozzle…</option>
              {(nozzles.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
            </Select>
          </Field>
          <Field label="Cumulative totalizer (litres)" required hint="Enter the value shown on the dispenser, not the litres sold.">
            <Input inputMode="decimal" value={manualValue} onChange={(e) => setManualValue(e.target.value)} placeholder="e.g. 58342.750" />
          </Field>
          <Field label="Reason / note">
            <Textarea value={manualNote} onChange={(e) => setManualNote(e.target.value)} placeholder="Gateway offline, meter replaced…" />
          </Field>
        </div>
      </Modal>
    </>
  );
}
