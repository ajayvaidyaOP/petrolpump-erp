import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Copy, HardDrive, KeyRound, Plus, RefreshCw, Radio, TriangleAlert, Zap } from 'lucide-react';
import { apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useAuth, useStations } from '../lib/auth';
import { formatDateTime, timeAgo } from '../lib/format';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
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
  useToast,
} from '../components/ui';

interface Device {
  id: string;
  stationId: string;
  code: string;
  name: string;
  type: string;
  vendor: string | null;
  model: string | null;
  serialNumber: string | null;
  protocol: string | null;
  status: string;
  isSimulated: number;
  online: boolean;
  apiKeyPrefix: string;
  keyVersion: number;
  lastHeartbeatAt: string | null;
  lastSyncAt: string | null;
  lastEventAt: string | null;
  pendingEvents: number;
  notes: string | null;
}

interface DeviceEvent {
  id: string;
  eventId: string;
  eventType: string;
  sequence: number;
  occurredAt: string;
  receivedAt: string;
  status: string;
  error: string | null;
  payload: Record<string, unknown> | null;
}

interface Diagnostics {
  serverTime: string;
  onlineWindowMinutes: number;
  eventCounts: Record<string, number>;
  lastEventAt: string | null;
  realHardwareVerified: boolean;
  disclaimer: string;
  recentErrors: { id: string; code: string; message: string; createdAt: string }[];
  adapterContract: { version: string; inboundAuth: string; eventTypes: string[]; idempotency: string };
}

export default function Devices() {
  const { can, me } = useAuth();
  const { stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({
    code: '',
    name: '',
    type: 'GATEWAY',
    vendor: '',
    model: '',
    serialNumber: '',
    protocol: '',
    simulated: true,
  });
  const [issuedKey, setIssuedKey] = useState<{ apiKey: string; device: string; warning: string } | null>(null);
  const [eventsFor, setEventsFor] = useState<Device | null>(null);
  const [eventPage, setEventPage] = useState(1);
  const [confirmDisable, setConfirmDisable] = useState<Device | null>(null);

  const devices = useQuery<Device[]>({
    queryKey: ['devices', stationId],
    queryFn: () => apiGet<Device[]>(`/devices?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });
  const diagnostics = useQuery<Diagnostics>({
    queryKey: ['diagnostics', stationId],
    queryFn: () => apiGet<Diagnostics>(`/hardware/diagnostics?stationId=${stationId}`),
    enabled: Boolean(stationId),
    refetchInterval: 30_000,
  });
  const events = useQuery<{ data: DeviceEvent[]; meta: ListMeta }>({
    queryKey: ['device-events', eventsFor?.id, eventPage],
    queryFn: () => apiList<DeviceEvent>(`/devices/${eventsFor?.id}/events?page=${eventPage}&pageSize=10`),
    enabled: Boolean(eventsFor),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['devices'] });
    void queryClient.invalidateQueries({ queryKey: ['diagnostics'] });
  };

  const create = useMutation({
    mutationFn: () =>
      apiPost<Device & { apiKey: string; warning: string }>('/devices', {
        stationId,
        code: form.code,
        name: form.name,
        type: form.type,
        vendor: form.vendor || null,
        model: form.model || null,
        serialNumber: form.serialNumber || null,
        protocol: form.protocol || null,
        isSimulated: form.simulated,
      }),
    onSuccess: (device) => {
      setCreateOpen(false);
      setIssuedKey({ apiKey: device.apiKey, device: device.name, warning: device.warning });
      setForm({ code: '', name: '', type: 'GATEWAY', vendor: '', model: '', serialNumber: '', protocol: '', simulated: true });
      invalidate();
    },
    onError: (error: Error) => toast('Could not register device', { tone: 'danger', description: error.message }),
  });

  const rotate = useMutation({
    mutationFn: (id: string) => apiPost<{ apiKey: string; keyVersion: number; warning: string }>(`/devices/${id}/rotate-key`),
    onSuccess: (data, id) => {
      const device = (devices.data ?? []).find((d) => d.id === id);
      setIssuedKey({ apiKey: data.apiKey, device: device?.name ?? 'gateway', warning: data.warning });
      invalidate();
    },
    onError: (error: Error) => toast('Key rotation failed', { tone: 'danger', description: error.message }),
  });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => apiPatch<Device>(`/devices/${id}`, { status }),
    onSuccess: () => {
      toast('Device updated', { tone: 'success' });
      setConfirmDisable(null);
      invalidate();
    },
    onError: (error: Error) => toast('Update failed', { tone: 'danger', description: error.message }),
  });

  const simulate = useMutation({
    mutationFn: () => apiPost<{ simulated: boolean; generated: unknown[] }>('/demo/simulate', { stationId, events: 2 }),
    onSuccess: (data) => {
      toast('Simulated events injected', {
        tone: 'info',
        description: `${data.generated.length} SIMULATED totalizer readings — never real hardware data.`,
      });
      void queryClient.invalidateQueries({ queryKey: ['readings'] });
      invalidate();
    },
    onError: (error: Error) => toast('Simulation failed', { tone: 'danger', description: error.message }),
  });

  const rows = devices.data ?? [];
  const online = rows.filter((d) => d.online).length;
  const pending = rows.reduce((acc, d) => acc + d.pendingEvents, 0);
  const simulated = rows.filter((d) => d.isSimulated).length;

  return (
    <div>
      <PageHeader
        title="Gateways & Devices"
        description="Hardware gateways authenticate with a device key, buffer locally and push idempotent events. This screen shows liveness, credential versions and raw event delivery."
        actions={
          <div className="flex flex-wrap gap-2">
            <ExportCsvButton path={`/devices?stationId=${stationId}`} filename="petroflow-devices" />
            {me?.demoMode && can('devices.write') && simulated > 0 ? (
              <Button size="sm" variant="outline" loading={simulate.isPending} onClick={() => simulate.mutate()}>
                <Zap className="h-4 w-4" /> Inject simulated reading
              </Button>
            ) : null}
            {can('devices.write') ? (
              <Button size="sm" variant="primary" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4" /> Register gateway
              </Button>
            ) : null}
          </div>
        }
      />

      <DemoNotice>
        No real dispenser or forecourt controller is connected in this environment. Simulated gateways are labelled SIMULATED
        and implement the internal adapter contract only — real hardware support is unverified.
      </DemoNotice>

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Gateways" value={rows.length} icon={<HardDrive className="h-4 w-4" />} />
        <StatCard label="Online" value={online} tone={online > 0 ? 'success' : 'warning'} hint={`Heartbeat within ${diagnostics.data?.onlineWindowMinutes ?? 5} min`} />
        <StatCard label="Pending events" value={pending} tone={pending ? 'warning' : 'default'} hint="Received but not yet processed." />
        <StatCard label="Simulated" value={simulated} hint="Never a substitute for certified meter readings." />
      </div>

      {devices.isLoading ? (
        <LoadingBlock />
      ) : devices.isError ? (
        <ErrorState error={devices.error} onRetry={() => void devices.refetch()} />
      ) : rows.length === 0 ? (
        <Card>
          <EmptyState
            title="No gateways registered"
            description="Register the on-site gateway to receive totalizer readings. The API key is shown once and stored hashed."
            action={
              can('devices.write') ? (
                <Button onClick={() => setCreateOpen(true)}>
                  <Plus className="h-4 w-4" /> Register gateway
                </Button>
              ) : undefined
            }
          />
        </Card>
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Gateway</th>
                  <th>Status</th>
                  <th>Last heartbeat</th>
                  <th>Last event</th>
                  <th className="text-right">Pending</th>
                  <th>Credential</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((device) => (
                  <tr key={device.id}>
                    <td>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-ink-100">{device.name}</span>
                        <Badge tone="neutral">{device.code}</Badge>
                        {device.isSimulated ? <Badge tone="warning">SIMULATED</Badge> : <Badge tone="info">REAL · UNVERIFIED</Badge>}
                      </div>
                      <p className="mt-0.5 text-xs text-ink-500">
                        {[device.vendor, device.model, device.serialNumber].filter(Boolean).join(' · ') || 'No vendor metadata'}
                      </p>
                    </td>
                    <td>
                      <div className="flex flex-col gap-1">
                        <StatusBadge status={device.status} />
                        <span className={device.online ? 'text-[11px] text-ok' : 'text-[11px] text-danger'}>
                          {device.online ? 'reachable' : 'offline'}
                        </span>
                      </div>
                    </td>
                    <td className="text-xs text-ink-400">
                      {device.lastHeartbeatAt ? timeAgo(device.lastHeartbeatAt) : 'Never'}
                      <span className="block text-ink-500">{formatDateTime(device.lastHeartbeatAt)}</span>
                    </td>
                    <td className="text-xs text-ink-400">
                      {device.lastEventAt ? timeAgo(device.lastEventAt) : 'None yet'}
                      <span className="block text-ink-500">sync {formatDateTime(device.lastSyncAt)}</span>
                    </td>
                    <td className="text-right">
                      {device.pendingEvents > 0 ? <Badge tone="warning">{device.pendingEvents}</Badge> : <span className="text-ink-500">0</span>}
                    </td>
                    <td className="text-xs text-ink-400">
                      {device.apiKeyPrefix}…
                      <span className="block text-ink-500">v{device.keyVersion}</span>
                    </td>
                    <td className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => { setEventsFor(device); setEventPage(1); }}>
                          Events
                        </Button>
                        {can('devices.write') ? (
                          <>
                            <Button
                              size="sm"
                              variant="ghost"
                              loading={rotate.isPending && rotate.variables === device.id}
                              onClick={() => rotate.mutate(device.id)}
                              aria-label={`Rotate key for ${device.name}`}
                            >
                              <KeyRound className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() =>
                                device.status === 'DISABLED'
                                  ? setStatus.mutate({ id: device.id, status: 'OFFLINE' })
                                  : setConfirmDisable(device)
                              }
                            >
                              {device.status === 'DISABLED' ? 'Enable' : 'Disable'}
                            </Button>
                          </>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="Ingestion pipeline"
            subtitle="Raw events are stored before validation; readings and sales are separate derived records."
            action={<Button size="sm" variant="ghost" onClick={() => void diagnostics.refetch()}><RefreshCw className="h-3.5 w-3.5" /> Refresh</Button>}
          />
          <div className="grid grid-cols-2 gap-3 p-4 text-sm">
            {Object.entries(diagnostics.data?.eventCounts ?? {}).length === 0 ? (
              <p className="col-span-2 text-ink-500">No events received yet.</p>
            ) : (
              Object.entries(diagnostics.data?.eventCounts ?? {}).map(([status, count]) => (
                <div key={status} className="rounded-lg border border-carbon-700/60 bg-carbon-900/40 p-3">
                  <p className="text-[11px] uppercase tracking-wider text-ink-500">{status}</p>
                  <p className="num mt-1 text-lg text-ink-100">{count}</p>
                </div>
              ))
            )}
            <div className="callout-warn col-span-2">
              <TriangleAlert className="mr-1.5 inline h-3.5 w-3.5" />
              {diagnostics.data?.disclaimer ?? 'Real hardware has not been verified.'}
            </div>
          </div>
        </Card>

        <Card>
          <CardHeader title="Recent device errors" subtitle="Rejects are retained for replay; nothing is silently dropped." />
          {(diagnostics.data?.recentErrors ?? []).length === 0 ? (
            <EmptyState title="No device errors" description="The last batch of events was processed cleanly." />
          ) : (
            <div className="max-h-72 overflow-y-auto overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Code</th>
                    <th>Message</th>
                  </tr>
                </thead>
                <tbody>
                  {(diagnostics.data?.recentErrors ?? []).map((error) => (
                    <tr key={error.id}>
                      <td className="whitespace-nowrap text-xs text-ink-400">{formatDateTime(error.createdAt)}</td>
                      <td><Badge tone="danger">{error.code}</Badge></td>
                      <td className="text-xs text-ink-300">{error.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Register gateway"
        description="The plaintext API key is displayed once. Configure it on the gateway host as PETROFLOW_DEVICE_KEY."
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button loading={create.isPending} onClick={() => create.mutate()}>Register</Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Code" required hint="e.g. GW-BAN-01">
            <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          </Field>
          <Field label="Name" required>
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Device type">
            <Select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
              <option value="GATEWAY">Local gateway</option>
              <option value="CONTROLLER">Dispenser controller</option>
            </Select>
          </Field>
          <Field label="Vendor">
            <Input value={form.vendor} onChange={(e) => setForm({ ...form, vendor: e.target.value })} />
          </Field>
          <Field label="Model">
            <Input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
          </Field>
          <Field label="Serial number">
            <Input value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} />
          </Field>
          <Field label="Protocol" className="sm:col-span-2" hint="Record the vendor-documented protocol; none is assumed.">
            <Input value={form.protocol} onChange={(e) => setForm({ ...form, protocol: e.target.value })} />
          </Field>
          <Field label="Mode" className="sm:col-span-2">
            <Select value={form.simulated ? 'SIM' : 'REAL'} onChange={(e) => setForm({ ...form, simulated: e.target.value === 'SIM' })}>
              <option value="SIM">Simulated (demo / testing)</option>
              <option value="REAL">Real hardware (unverified until commissioned)</option>
            </Select>
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(issuedKey)}
        onClose={() => setIssuedKey(null)}
        title="Device key issued"
        description="This key is shown once. Store it on the gateway host; only its hash is kept in the database."
        footer={<Button onClick={() => setIssuedKey(null)}>I have stored this key</Button>}
      >
        <div className="space-y-3">
          <div className="rounded-lg border border-carbon-700 bg-inset p-3">
            <p className="text-[11px] uppercase tracking-wider text-ink-500">API key for {issuedKey?.device}</p>
            <code className="mt-1 block break-all font-mono text-sm text-accent">{issuedKey?.apiKey}</code>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              onClick={() => {
                void navigator.clipboard.writeText(issuedKey?.apiKey ?? '');
                toast('Copied to clipboard', { tone: 'success' });
              }}
            >
              <Copy className="h-4 w-4" /> Copy key
            </Button>
          </div>
          <p className="text-xs text-ink-500">{issuedKey?.warning}</p>
        </div>
      </Modal>

      <Modal
        open={Boolean(eventsFor)}
        onClose={() => setEventsFor(null)}
        size="lg"
        title={`Raw events — ${eventsFor?.name ?? ''}`}
        description="Payloads are stored verbatim. REPLAYED events are re-processed only when the original failed validation."
      >
        {events.isLoading ? (
          <LoadingBlock />
        ) : (events.data?.data ?? []).length === 0 ? (
          <EmptyState title="No events" description="This gateway has not delivered any events yet." />
        ) : (
          <>
            <div className="max-h-[420px] overflow-y-auto overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Event</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>Received</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {(events.data?.data ?? []).map((event) => (
                    <tr key={event.id}>
                      <td className="font-mono text-xs text-ink-300">{event.eventId}</td>
                      <td className="text-xs text-ink-400">{event.eventType}</td>
                      <td>
                        <StatusBadge status={event.status} />
                        {event.error ? <span className="mt-1 block text-[11px] text-danger">{event.error}</span> : null}
                      </td>
                      <td className="whitespace-nowrap text-xs text-ink-400">{formatDateTime(event.receivedAt)}</td>
                      <td className="text-right">
                        {can('devices.write') && ['REJECTED', 'ERROR'].includes(event.status) ? (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={async () => {
                              try {
                                await apiPost(`/devices/${eventsFor?.id}/events/${event.eventId}/replay`);
                                toast('Event replayed', { tone: 'success' });
                                void events.refetch();
                                invalidate();
                              } catch (error) {
                                toast('Replay failed', { tone: 'danger', description: (error as Error).message });
                              }
                            }}
                          >
                            <Activity className="h-3.5 w-3.5" /> Replay
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {events.data ? (
              <Pagination
                page={events.data.meta.page}
                pageSize={events.data.meta.pageSize}
                total={events.data.meta.total}
                totalPages={events.data.meta.totalPages}
                onPage={setEventPage}
              />
            ) : null}
          </>
        )}
      </Modal>

      <ConfirmDialog
        open={Boolean(confirmDisable)}
        title="Disable gateway?"
        message={
          <>
            <strong className="text-ink-200">{confirmDisable?.name}</strong> will stop authenticating immediately; the device key
            is invalidated. Historical events and readings are retained.
          </>
        }
        confirmLabel="Disable gateway"
        danger
        loading={setStatus.isPending}
        onClose={() => setConfirmDisable(null)}
        onConfirm={() => confirmDisable && setStatus.mutate({ id: confirmDisable.id, status: 'DISABLED' })}
      />
    </div>
  );
}
