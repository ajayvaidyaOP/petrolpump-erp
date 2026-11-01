import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fuel, Plus, Wrench } from 'lucide-react';
import { apiGet, apiPatch, apiPost } from '../lib/api';
import { DeleteRecordButton, ExportCsvButton } from '../components/OwnerActions';
import { useAuth, useStations } from '../lib/auth';
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

interface Product {
  id: string;
  code: string;
  name: string;
  color: string;
}
interface Dispenser {
  id: string;
  stationId: string;
  code: string;
  name: string;
  model: string | null;
  serialNumber: string;
  protocol: string | null;
  status: string;
}
interface Nozzle {
  id: string;
  stationId: string;
  dispenserId: string;
  productId: string;
  tankId: string | null;
  number: number;
  label: string;
  totalizerUnit: string;
  status: string;
}
interface TankMini {
  id: string;
  code: string;
  productId: string;
  stationId: string;
  status?: string;
}
interface Device {
  id: string;
  hardwareCode: string;
  status: string;
  online: boolean;
  lastHeartbeatAt: string | null;
}

export default function Dispensers() {
  const { can } = useAuth();
  const { stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [dispenserOpen, setDispenserOpen] = useState(false);
  const [nozzleFor, setNozzleFor] = useState<Dispenser | null>(null);
  const [form, setForm] = useState({ code: '', name: '', model: '', serialNumber: '', protocol: '' });
  const [nozzleForm, setNozzleForm] = useState({ number: '1', label: '', productId: '', tankId: '', totalizerUnit: 'L' });

  const products = useQuery<Product[]>({
    queryKey: ['products-all'],
    queryFn: () => apiGet<Product[]>('/catalog/products?pageSize=100'),
  });
  const dispensers = useQuery<Dispenser[]>({
    queryKey: ['dispensers', stationId],
    queryFn: () => apiGet<Dispenser[]>(`/catalog/dispensers?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });
  const nozzles = useQuery<Nozzle[]>({
    queryKey: ['nozzles', stationId],
    queryFn: () => apiGet<Nozzle[]>(`/catalog/nozzles?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });
  const tanks = useQuery<TankMini[]>({
    queryKey: ['tanks-min', stationId],
    queryFn: () => apiGet<TankMini[]>(`/catalog/tanks?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });
  const tankMap = new Map((tanks.data ?? []).map((t) => [t.id, t]));
  const devices = useQuery<Device[]>({
    queryKey: ['devices', stationId],
    queryFn: () => apiGet<Device[]>(`/devices?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId) && can('devices.read'),
  });

  const productMap = new Map((products.data ?? []).map((p) => [p.id, p]));

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['dispensers'] });
    void queryClient.invalidateQueries({ queryKey: ['nozzles'] });
  };

  const createDispenser = useMutation({
    mutationFn: () =>
      apiPost<Dispenser>('/catalog/dispensers', {
        stationId,
        code: form.code,
        name: form.name,
        model: form.model || null,
        serialNumber: form.serialNumber,
        protocol: form.protocol || null,
        status: 'ACTIVE',
      }),
    onSuccess: () => {
      toast('Dispenser added', { tone: 'success', description: 'Add nozzles and map each one to a product.' });
      setDispenserOpen(false);
      setForm({ code: '', name: '', model: '', serialNumber: '', protocol: '' });
      invalidate();
    },
    onError: (error: Error) => toast('Could not add dispenser', { tone: 'danger', description: error.message }),
  });

  const createNozzle = useMutation({
    mutationFn: () =>
      apiPost<Nozzle>('/catalog/nozzles', {
        stationId,
        dispenserId: nozzleFor?.id,
        productId: nozzleForm.productId,
        tankId: nozzleForm.tankId || null,
        number: Number(nozzleForm.number),
        label: nozzleForm.label,
        totalizerUnit: nozzleForm.totalizerUnit,
        status: 'ACTIVE',
      }),
    onSuccess: () => {
      toast('Nozzle mapped', { tone: 'success' });
      setNozzleFor(null);
      setNozzleForm({ number: '1', label: '', productId: '', tankId: '', totalizerUnit: 'L' });
      invalidate();
    },
    onError: (error: Error) => toast('Could not add nozzle', { tone: 'danger', description: error.message }),
  });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => apiPatch<Dispenser>(`/catalog/dispensers/${id}`, { status }),
    onSuccess: () => {
      toast('Dispenser updated', { tone: 'success' });
      invalidate();
    },
    onError: (error: Error) => toast('Update failed', { tone: 'danger', description: error.message }),
  });

  const remapTank = useMutation({
    mutationFn: ({ id, tankId }: { id: string; tankId: string | null }) =>
      apiPatch<Nozzle>(`/catalog/nozzles/${id}`, { tankId }),
    onSuccess: () => {
      toast('Nozzle-tank mapping updated', { tone: 'success', description: 'Dispensed volume from this nozzle will deduct from the mapped tank.' });
      invalidate();
    },
    onError: (error: Error) => toast('Could not update mapping', { tone: 'danger', description: error.message }),
  });

  const rows = dispensers.data ?? [];
  const allNozzles = nozzles.data ?? [];
  const unmapped = allNozzles.filter((n) => !productMap.has(n.productId)).length;

  return (
    <div>
      <PageHeader
        title="Dispensers & Nozzles"
        description="Physical dispensing units and the nozzles that report totalizers. Nozzle totals are aggregate estimates — they never substitute for a tank dip."
        actions={
          <>
            <ExportCsvButton path={`/catalog/dispensers?stationId=${stationId}`} filename="petroflow-dispensers" />
            {can('dispensers.write') ? (
              <Button variant="primary" size="sm" onClick={() => setDispenserOpen(true)}>
                <Plus className="h-4 w-4" /> Add dispenser
              </Button>
            ) : null}
          </>
        }
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-4">
        <StatCard label="Dispensers" value={rows.length} icon={<Fuel className="h-4 w-4" />} />
        <StatCard label="Nozzles" value={allNozzles.length} icon={<Wrench className="h-4 w-4" />} />
        <StatCard
          label="Unmapped nozzles"
          value={unmapped}
          tone={unmapped ? 'danger' : 'success'}
          hint="A nozzle without a valid product cannot value sales."
        />
        <StatCard
          label="Tank-unmapped"
          value={allNozzles.filter((n) => !n.tankId).length}
          tone={allNozzles.some((n) => !n.tankId) ? 'warning' : 'success'}
          hint="Unmapped nozzle volumes distribute proportionally across same-product tanks."
        />
        {can('devices.read') && (
          <StatCard
            label="Online gateways"
            value={(devices.data ?? []).filter((d) => d.online).length}
            hint="Gateways push readings; no direct browser-to-hardware calls."
          />
        )}
      </div>

      {dispensers.isLoading ? (
        <LoadingBlock />
      ) : dispensers.isError ? (
        <ErrorState error={dispensers.error} onRetry={() => void dispensers.refetch()} />
      ) : rows.length === 0 ? (
        <Card>
          <EmptyState
            title="No dispensers configured"
            description="Register each dispenser and its nozzles before capturing shift readings."
            action={
              can('dispensers.write') ? (
                <Button onClick={() => setDispenserOpen(true)}>
                  <Plus className="h-4 w-4" /> Add dispenser
                </Button>
              ) : undefined
            }
          />
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {rows.map((dispenser) => {
            const own = allNozzles.filter((n) => n.dispenserId === dispenser.id);
            return (
              <Card key={dispenser.id}>
                <CardHeader
                  title={
                    <span className="flex items-center gap-2">
                      {dispenser.name} <Badge tone="neutral">{dispenser.code}</Badge>
                    </span>
                  }
                  subtitle={`Serial ${dispenser.serialNumber}${dispenser.model ? ` · ${dispenser.model}` : ''}${dispenser.protocol ? ` · ${dispenser.protocol}` : ''}`}
                  action={
                    <div className="flex items-center gap-2">
                      <StatusBadge status={dispenser.status} />
                      {can('dispensers.write') ? (
                        <>
                          <Button size="sm" variant="outline" onClick={() => setNozzleFor(dispenser)}>
                            <Plus className="h-3.5 w-3.5" /> Nozzle
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              setStatus.mutate({ id: dispenser.id, status: dispenser.status === 'ACTIVE' ? 'MAINTENANCE' : 'ACTIVE' })
                            }
                          >
                            {dispenser.status === 'ACTIVE' ? 'Maintenance' : 'Reactivate'}
                          </Button>
                          <DeleteRecordButton path={`/catalog/dispensers/${dispenser.id}`} label={dispenser.name} onDeleted={invalidate} />
                        </>
                      ) : null}
                    </div>
                  }
                />
                <div className="overflow-x-auto">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Nozzle</th>
                        <th>Product</th>
                        <th>Tank (kis tank se kategi)</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {own.length === 0 ? (
                        <tr>
                          <td colSpan={4} className="text-center text-sm text-ink-500">
                            No nozzles mapped yet.
                          </td>
                        </tr>
                      ) : (
                        own.map((nozzle) => {
                          const product = productMap.get(nozzle.productId);
                          return (
                            <tr key={nozzle.id}>
                              <td>
                                <span className="font-medium text-ink-100">{nozzle.label}</span>
                                <span className="ml-2 text-xs text-ink-500">#{nozzle.number}</span>
                              </td>
                              <td>
                                {product ? (
                                  <span className="inline-flex items-center gap-1.5">
                                    <span className="h-2 w-2 rounded-full" style={{ background: product.color }} />
                                    {product.name}
                                  </span>
                                ) : (
                                  <Badge tone="danger">Unmapped</Badge>
                                )}
                              </td>
                              <td>
                                {can('dispensers.write') ? (
                                  <Select
                                    className="max-w-fit px-2 py-1 text-xs"
                                    value={nozzle.tankId ?? ''}
                                    aria-label="Nozzle tank select"
                                    onChange={(e) => remapTank.mutate({ id: nozzle.id, tankId: e.target.value || null })}
                                  >
                                    <option value="">— Tank jodo —</option>
                                    {(tanks.data ?? [])
                                      .filter((t) => t.productId === nozzle.productId)
                                      .map((t) => (
                                        <option key={t.id} value={t.id}>{t.code}</option>
                                      ))}
                                  </Select>
                                ) : nozzle.tankId ? (
                                  <span className="text-ink-300">{tankMap.get(nozzle.tankId)?.code ?? '—'}</span>
                                ) : (
                                  <Badge tone="warning">Bina tank</Badge>
                                )}
                              </td>
                              <td>
                                <StatusBadge status={nozzle.status} />
                              </td>
                            </tr>
                          );
                        })
                      )}
                    </tbody>
                  </table>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      <Modal
        open={dispenserOpen}
        onClose={() => setDispenserOpen(false)}
        title="Add dispenser"
        description="The model, serial and protocol are recorded for maintenance and gateway mapping. No vendor protocol is assumed."
        footer={
          <>
            <Button variant="ghost" onClick={() => setDispenserOpen(false)}>
              Cancel
            </Button>
            <Button loading={createDispenser.isPending} onClick={() => createDispenser.mutate()}>
              Add dispenser
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Code" required>
            <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          </Field>
          <Field label="Name" required>
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Model">
            <Input value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
          </Field>
          <Field label="Serial number" required>
            <Input value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} />
          </Field>
          <Field label="Protocol (optional)" className="sm:col-span-2" hint="e.g. vendor-specific RS-485 / Modbus register map. Documented per site.">
            <Input value={form.protocol} onChange={(e) => setForm({ ...form, protocol: e.target.value })} />
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(nozzleFor)}
        onClose={() => setNozzleFor(null)}
        title={`Add nozzle — ${nozzleFor?.name ?? ''}`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setNozzleFor(null)}>
              Cancel
            </Button>
            <Button loading={createNozzle.isPending} onClick={() => createNozzle.mutate()}>
              Add nozzle
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Nozzle number" required>
            <Input type="number" min={1} max={99} value={nozzleForm.number} onChange={(e) => setNozzleForm({ ...nozzleForm, number: e.target.value })} />
          </Field>
          <Field label="Label" required>
            <Input value={nozzleForm.label} onChange={(e) => setNozzleForm({ ...nozzleForm, label: e.target.value })} placeholder="P1-N1" />
          </Field>
          <Field label="Product" required className="sm:col-span-2">
            <Select value={nozzleForm.productId} onChange={(e) => setNozzleForm({ ...nozzleForm, productId: e.target.value, tankId: '' })}>
              <option value="">Select a product…</option>
              {(products.data ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.code})
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Totalizer unit">
            <Input value={nozzleForm.totalizerUnit} onChange={(e) => setNozzleForm({ ...nozzleForm, totalizerUnit: e.target.value })} />
          </Field>
          <Field
            label="Source underground tank"
            required
            className="sm:col-span-2"
            hint="Critical mapping: volume dispensed from this nozzle deducts directly from the selected tank's book stock."
          >
            <Select
              value={nozzleForm.tankId}
              onChange={(e) => setNozzleForm({ ...nozzleForm, tankId: e.target.value })}
              disabled={!nozzleForm.productId}
            >
              <option value="">{nozzleForm.productId ? 'Select tank…' : 'Select fuel product first'}</option>
              {(tanks.data ?? [])
                .filter((t) => t.productId === nozzleForm.productId && (!t.status || t.status === 'ACTIVE'))
                .map((t) => (
                  <option key={t.id} value={t.id}>{t.code} ({productMap.get(t.productId)?.name ?? ''})</option>
                ))}
            </Select>
          </Field>
        </div>
      </Modal>
    </div>
  );
}
