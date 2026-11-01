import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, MapPin, Pencil, Plus, Store } from 'lucide-react';
import { apiGet, apiPatch, apiPost } from '../lib/api';
import { DeleteRecordButton, ExportCsvButton } from '../components/OwnerActions';
import { useAuth } from '../lib/auth';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingBlock,
  Modal,
  PageHeader,
  Select,
  StatCard,
  StatusBadge,
  useToast,
} from '../components/ui';
import { formatDateTime } from '../lib/format';

interface StationRow {
  id: string;
  code: string;
  name: string;
  address: string | null;
  city: string | null;
  state: string | null;
  pincode: string | null;
  status: string;
  isDemo: number;
  tankCount: number;
  dispenserCount: number;
  nozzleCount: number;
  deviceCount: number;
  createdAt: string;
  updatedAt: string;
}

const blankStation = { code: '', name: '', address: '', city: '', state: 'Maharashtra', pincode: '' };

export default function Stations() {
  const { can } = useAuth();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [edit, setEdit] = useState<StationRow | null>(null);
  const [form, setForm] = useState({ ...blankStation });
  const [editForm, setEditForm] = useState({ name: '', address: '', city: '', state: '', pincode: '', status: 'ACTIVE' });

  const stations = useQuery<StationRow[]>({
    queryKey: ['stations'],
    queryFn: () => apiGet<StationRow[]>('/catalog/stations'),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['stations'] });
    void queryClient.invalidateQueries({ queryKey: ['me'] });
  };

  const create = useMutation({
    mutationFn: () => apiPost<StationRow>('/catalog/stations', form),
    onSuccess: () => {
      toast('Station created', { tone: 'success', description: `${form.name} (${form.code}) is ready to configure.` });
      setCreateOpen(false);
      setForm({ ...blankStation });
      invalidate();
    },
    onError: (error: Error) => toast('Could not create station', { tone: 'danger', description: error.message }),
  });

  const update = useMutation({
    mutationFn: () => apiPatch<StationRow>(`/catalog/stations/${edit?.id}`, editForm),
    onSuccess: () => {
      toast('Station updated', { tone: 'success' });
      setEdit(null);
      invalidate();
    },
    onError: (error: Error) => toast('Update failed', { tone: 'danger', description: error.message }),
  });

  const rows = stations.data ?? [];
  const active = rows.filter((r) => r.status === 'ACTIVE').length;

  return (
    <div>
      <PageHeader
        title="Stations"
        description="Each station has its own tanks, dispensers, shifts, staff access and financial ledger. Data never crosses between stations or organizations."
        actions={
          <>
            <ExportCsvButton path="/catalog/stations" filename="petroflow-stations" />
            {can('stations.write') ? (
              <Button variant="primary" size="sm" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4" /> New station
              </Button>
            ) : null}
          </>
        }
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="Stations" value={rows.length} icon={<Store className="h-4 w-4" />} />
        <StatCard label="Active" value={active} tone="success" icon={<Building2 className="h-4 w-4" />} />
        <StatCard
          label="Total nozzles"
          value={rows.reduce((acc, r) => acc + Number(r.nozzleCount ?? 0), 0)}
          hint="Nozzle totals are aggregate estimates at the pump, not tank reconciliation."
        />
      </div>

      <Card>
        <CardHeader title="Organization stations" subtitle="Configuration is scoped to the selected station in the sidebar." />
        {stations.isLoading ? (
          <LoadingBlock />
        ) : stations.isError ? (
          <ErrorState error={stations.error} onRetry={() => void stations.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState title="No stations yet" description="Create the first station to start operations." />
        ) : (
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Station</th>
                  <th>Location</th>
                  <th className="text-right">Tanks</th>
                  <th className="text-right">Dispensers</th>
                  <th className="text-right">Nozzles</th>
                  <th className="text-right">Gateways</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((station) => (
                  <tr key={station.id}>
                    <td>
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-ink-100">{station.name}</span>
                        <Badge tone="neutral">{station.code}</Badge>
                        {station.isDemo ? <Badge tone="warning">DEMO</Badge> : null}
                      </div>
                    </td>
                    <td className="text-ink-400">
                      <span className="inline-flex items-center gap-1">
                        <MapPin className="h-3.5 w-3.5" />
                        {[station.city, station.state].filter(Boolean).join(', ') || '—'}
                      </span>
                    </td>
                    <td className="text-right tabular-nums">{station.tankCount}</td>
                    <td className="text-right tabular-nums">{station.dispenserCount}</td>
                    <td className="text-right tabular-nums">{station.nozzleCount}</td>
                    <td className="text-right tabular-nums">{station.deviceCount}</td>
                    <td>
                      <StatusBadge status={station.status} />
                    </td>
                    <td className="text-ink-500">{formatDateTime(station.createdAt)}</td>
                    <td className="text-right">
                      {can('stations.write') ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            setEdit(station);
                            setEditForm({
                              name: station.name,
                              address: station.address ?? '',
                              city: station.city ?? '',
                              state: station.state ?? '',
                              pincode: station.pincode ?? '',
                              status: station.status,
                            });
                          }}
                        >
                          <Pencil className="h-3.5 w-3.5" /> Edit
                        </Button>
                      ) : null}
                      <DeleteRecordButton path={`/catalog/stations/${station.id}`} label={station.name} onDeleted={invalidate} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="New station"
        description="Stations are created inside your organization. Additional stations depend on your subscription limit."
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button loading={create.isPending} onClick={() => create.mutate()}>
              Create station
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Station code" required hint="Short internal code, e.g. SUN-BAN">
            <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          </Field>
          <Field label="Station name" required>
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Address" className="sm:col-span-2">
            <Input value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
          </Field>
          <Field label="City">
            <Input value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} />
          </Field>
          <Field label="State">
            <Input value={form.state} onChange={(e) => setForm({ ...form, state: e.target.value })} />
          </Field>
          <Field label="Pincode">
            <Input value={form.pincode} onChange={(e) => setForm({ ...form, pincode: e.target.value })} />
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(edit)}
        onClose={() => setEdit(null)}
        title={`Edit ${edit?.name ?? 'station'}`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setEdit(null)}>
              Cancel
            </Button>
            <Button loading={update.isPending} onClick={() => update.mutate()}>
              Save changes
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name" required className="sm:col-span-2">
            <Input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} />
          </Field>
          <Field label="Address" className="sm:col-span-2">
            <Input value={editForm.address} onChange={(e) => setEditForm({ ...editForm, address: e.target.value })} />
          </Field>
          <Field label="City">
            <Input value={editForm.city} onChange={(e) => setEditForm({ ...editForm, city: e.target.value })} />
          </Field>
          <Field label="State">
            <Input value={editForm.state} onChange={(e) => setEditForm({ ...editForm, state: e.target.value })} />
          </Field>
          <Field label="Pincode">
            <Input value={editForm.pincode} onChange={(e) => setEditForm({ ...editForm, pincode: e.target.value })} />
          </Field>
          <Field label="Status">
            <Select value={editForm.status} onChange={(e) => setEditForm({ ...editForm, status: e.target.value })}>
              <option value="ACTIVE">Active</option>
              <option value="SUSPENDED">Suspended</option>
              <option value="CLOSED">Closed</option>
            </Select>
          </Field>
        </div>
        <p className="mt-3 rounded-lg border border-carbon-700/60 bg-carbon-900/40 p-3 text-xs text-ink-500">
          Station status does not delete history. Suspending a station blocks new operational entries while preserving ledgers and audit logs.
        </p>
      </Modal>
    </div>
  );
}
