import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FlaskConical, Plus, RefreshCw, Thermometer, Trash2 } from 'lucide-react';
import { apiDelete, apiList, apiPost, type ListMeta } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useIdempotencyKey } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDate, formatNumber, todayIst } from '../lib/format';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
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
  TableScrollHint,
  useToast,
} from '../components/ui';

interface DensityRow {
  id: string;
  businessDate: string;
  tankId: string;
  tankCode: string;
  tankName: string;
  productName: string;
  productColor: string;
  observedDensity: string;
  observedTempC: string;
  densityAt15c: string;
  specMin: string | null;
  specMax: string | null;
  withinSpec: number;
  source: string;
  note: string | null;
  checkedByName: string | null;
  checkedAt: string;
}

interface TankOption {
  id: string;
  code: string;
  name: string;
  productId: string;
  productName: string;
  productColor: string;
}
interface ProductOption {
  id: string;
  category: string;
}

const SPEC: Record<string, [number, number]> = { PETROL: [720, 775], DIESEL: [810, 845] };

function computeLegacyDensity15cEstimate(observedKgM3: number, tempC: number): number {
  const dt = tempC - 15;
  const hyd = 1 - 0.000023 * dt - 0.00000002 * dt * dt;
  const rhoT = observedKgM3 * hyd;
  let rho15 = rhoT;
  for (let i = 0; i < 20; i += 1) {
    let alpha: number;
    if (rho15 < 770) {
      alpha = (346.4228 + 0.4388 * rho15) / (rho15 * rho15);
    } else if (rho15 < 787.5) {
      alpha = -0.00336312 + 2680.3206 / (rho15 * rho15);
    } else if (rho15 < 839) {
      alpha = 594.5418 / (rho15 * rho15);
    } else {
      alpha = (186.9696 + 0.4862 * rho15) / (rho15 * rho15);
    }
    const vcf = Math.exp(-alpha * dt * (1 + 0.8 * alpha * dt));
    const next = rhoT / vcf;
    if (Math.abs(next - rho15) < 1e-7) {
      rho15 = next;
      break;
    }
    rho15 = next;
  }
  return rho15;
}

export default function Density() {
  const { can } = useAuth();
  const { station, stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [from, setFrom] = useState(() => {
    const d = new Date(`${todayIst()}T12:00:00+05:30`);
    d.setDate(d.getDate() - 14);
    return d.toISOString().slice(0, 10);
  });
  const [to, setTo] = useState(todayIst());
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);

  const checks = useQuery<{ data: DensityRow[]; meta: ListMeta }>({
    queryKey: ['density', stationId, from, to, page],
    queryFn: () =>
      apiList<DensityRow>(`/density?stationId=${stationId}&from=${from}&to=${to}&page=${page}&pageSize=25`),
    enabled: Boolean(stationId),
  });

  const remove = useMutation({
    mutationFn: () => apiDelete(`/density/${deleteId}`),
    onSuccess: () => {
      toast('Density entry removed');
      setDeleteId(null);
      void queryClient.invalidateQueries({ queryKey: ['density'] });
    },
    onError: (error: Error) => toast('Could not delete entry', { tone: 'danger', description: error.message }),
  });

  const rows = checks.data?.data ?? [];
  const outOfSpec = rows.filter((r) => Number(r.withinSpec) === 0).length;
  const todayChecked = rows.filter((r) => r.businessDate === todayIst()).length;

  return (
    <>
      <PageHeader
        title="Morning density & temperature register"
        description={`${station?.name ?? 'Station'} · Uncertified legacy 15°C density estimate and screening bands — not statutory compliance evidence`}
        actions={
          <>
            <ExportCsvButton path={`/density?stationId=${stationId}&from=${from}&to=${to}`} filename="petroflow-density" />
            <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="w-auto" aria-label="From date" />
            <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="w-auto" aria-label="To date" />
            <Button size="sm" variant="ghost" icon={<RefreshCw className="h-3.5 w-3.5" />} onClick={() => void checks.refetch()}>
              Refresh
            </Button>
            {can('density.write') && (
              <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
                New density check
              </Button>
            )}
          </>
        }
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Checked today"
          value={todayChecked}
          hint="Morning hydrometer checks logged today"
          icon={<Thermometer className="h-4 w-4" />}
          tone={todayChecked > 0 ? 'success' : 'default'}
        />
        <StatCard
          label="Period entries"
          value={checks.data?.meta.total ?? rows.length}
          hint={`${formatDate(from)} – ${formatDate(to)}`}
          icon={<FlaskConical className="h-4 w-4" />}
        />
        <StatCard
          label="Outside screening band"
          value={outOfSpec}
          hint="Review sample; not a certified compliance result"
          tone={outOfSpec > 0 ? 'warning' : 'success'}
        />
      </div>

      {outOfSpec > 0 && (
        <div className="callout-danger mb-4 flex items-center gap-3">
          <FlaskConical className="h-4 w-4 shrink-0 text-danger" />
          <span>
            <strong>{outOfSpec} sample(s) outside the configured screening band</strong> in this date range. Retain the sample and review with the station manager; this estimate is not certified ASTM/IS compliance evidence.
          </span>
        </div>
      )}

      <Card>
        <CardHeader
          title="Density inspection log"
          subtitle="Legacy thermal estimate @15°C. Screening bands: petrol 720–775 kg/m³ · diesel 810–845 kg/m³. Not certified ASTM/IS compliance."
          action={<Badge tone="accent">{checks.data?.meta.total ?? rows.length} entries</Badge>}
        />
        <TableScrollHint />
        {checks.isLoading ? (
          <LoadingBlock rows={5} />
        ) : checks.isError ? (
          <div className="p-4">
            <ErrorState error={checks.error} onRetry={() => void checks.refetch()} />
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No density checks in this period"
            description="Record daily morning hydrometer and temperature readings for every underground tank."
            icon={<Thermometer className="h-4 w-4" />}
            action={
              can('density.write') ? (
                <Button size="sm" variant="primary" onClick={() => setCreateOpen(true)}>
                  Record first check
                </Button>
              ) : undefined
            }
          />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table min-w-[860px]">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Tank · Product</th>
                    <th className="text-right">Observed (kg/m³)</th>
                    <th className="text-right">Temp °C</th>
                    <th className="text-right">Density @15°C</th>
                    <th>Spec range</th>
                    <th>Result</th>
                    <th>Source</th>
                    <th>Checked by</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="whitespace-nowrap text-xs">{formatDate(row.businessDate)}</td>
                      <td>
                        <span className="flex items-center gap-1.5 text-xs">
                          <span className="h-2 w-2 rounded-full" style={{ background: row.productColor }} />
                          <span className="font-semibold text-ink-100">{row.tankCode}</span> · {row.productName}
                        </span>
                      </td>
                      <td className="num text-right">{formatNumber(row.observedDensity, 1)}</td>
                      <td className="num text-right">{formatNumber(row.observedTempC, 1)}</td>
                      <td className="num text-right font-semibold text-ink-100">{formatNumber(row.densityAt15c, 1)}</td>
                      <td className="num text-xs text-ink-500">
                        {row.specMin && row.specMax ? `${row.specMin}–${row.specMax}` : '—'}
                      </td>
                      <td>
                        {Number(row.withinSpec) === 1 ? (
                          <Badge tone="success" dot>
                            Within band
                          </Badge>
                        ) : (
                          <Badge tone="danger" dot>
                            Outside band
                          </Badge>
                        )}
                      </td>
                      <td className="text-xs text-ink-400">{row.source.replace('_', ' ')}</td>
                      <td className="text-xs text-ink-400">{row.checkedByName ?? '—'}</td>
                      <td className="text-right">
                        {can('dayclose.manage') && (
                          <Button
                            size="sm"
                            variant="ghost"
                            aria-label="Delete density entry"
                            onClick={() => setDeleteId(row.id)}
                          >
                            <Trash2 className="h-3.5 w-3.5 text-danger" />
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {checks.data && (
              <Pagination
                page={checks.data.meta.page}
                pageSize={checks.data.meta.pageSize}
                total={checks.data.meta.total}
                totalPages={checks.data.meta.totalPages}
                onPage={setPage}
              />
            )}
          </>
        )}
      </Card>

      <CreateDensityModal open={createOpen} onClose={() => setCreateOpen(false)} stationId={stationId} />
      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={() => remove.mutate()}
        title="Delete density entry?"
        message="This removes the entry from the register while retaining an immutable audit trail."
        confirmLabel="Delete"
        danger
        loading={remove.isPending}
      />
    </>
  );
}

function CreateDensityModal({
  open,
  onClose,
  stationId,
}: {
  open: boolean;
  onClose: () => void;
  stationId: string | null;
}) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [tankId, setTankId] = useState('');
  const [date, setDate] = useState(todayIst());
  const [observed, setObserved] = useState('');
  const [temp, setTemp] = useState('');
  const [source, setSource] = useState('NOZZLE');
  const [note, setNote] = useState('');

  const tanks = useQuery<{ data: TankOption[]; meta: ListMeta }>({
    queryKey: ['catalog-tanks', stationId],
    queryFn: () => apiList<TankOption>(`/catalog/tanks?stationId=${stationId}&pageSize=100`),
    enabled: open && Boolean(stationId),
  });
  const products = useQuery<{ data: ProductOption[]; meta: ListMeta }>({
    queryKey: ['catalog-products-min'],
    queryFn: () => apiList<ProductOption>('/catalog/products?pageSize=100'),
    enabled: open,
  });

  const selectedTank = tanks.data?.data.find((t) => t.id === tankId);
  const category = products.data?.data.find((p) => p.id === selectedTank?.productId)?.category ?? '';
  const preview = useMemo(() => {
    const o = Number(observed);
    const t = Number(temp);
    if (!o || o < 653 || o > 1075 || Number.isNaN(t) || t < -20 || t > 65 || observed === '' || temp === '') return null;
    const at15 = Math.round(computeLegacyDensity15cEstimate(o, t) * 10) / 10;
    if (at15 < 653 || at15 > 1075) return null;
    const spec = SPEC[category];
    return { at15: at15.toFixed(1), within: spec ? at15 >= spec[0] && at15 <= spec[1] : false, spec };
  }, [observed, temp, category]);

  const densityPayload = {
        stationId,
        tankId,
        businessDate: date,
        observedDensity: observed,
        observedTempC: temp,
        source,
        note: note || undefined,
      };
  const densityKey = useIdempotencyKey('density-check', densityPayload);
  const create = useMutation({
    mutationFn: () =>
      apiPost('/density', densityPayload, densityKey.key),
    onSuccess: () => {
      densityKey.reset();
      toast('Density check saved', { description: preview ? `@15°C: ${preview.at15} kg/m³` : undefined });
      setObserved('');
      setTemp('');
      setNote('');
      onClose();
      void queryClient.invalidateQueries({ queryKey: ['density'] });
    },
    onError: (error: Error) => toast('Could not save check', { tone: 'danger', description: error.message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New density check"
      description="Enter hydrometer and thermometer observations. Always enter density in kg/m³ (e.g. 742.5, not 0.742)."
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={create.isPending}
            disabled={!tankId || !observed || !temp}
            onClick={() => create.mutate()}
          >
            Save check
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Tank" required>
          <Select value={tankId} onChange={(e) => setTankId(e.target.value)}>
            <option value="">Select tank…</option>
            {(tanks.data?.data ?? []).map((tank) => (
              <option key={tank.id} value={tank.id}>
                {tank.code} · {tank.name}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Business date" required>
          <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Observed density (kg/m³)" required>
            <Input inputMode="decimal" placeholder="742.5" value={observed} onChange={(e) => setObserved(e.target.value)} />
          </Field>
          <Field label="Temperature (°C)" required>
            <Input inputMode="decimal" placeholder="31.0" value={temp} onChange={(e) => setTemp(e.target.value)} />
          </Field>
        </div>
        <Field label="Sample source">
          <Select value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="NOZZLE">Dispenser nozzle</option>
            <option value="TANK">Underground tank</option>
            <option value="TT_SAMPLE">Tanker compartment sample</option>
          </Select>
        </Field>
        <Field label="Note">
          <Input placeholder="e.g. 07:00 morning inspection" value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        {preview && (
          <div className={preview.within ? 'callout-ok' : 'callout-danger'}>
            Density @15°C ≈ <strong className="num">{preview.at15} kg/m³</strong>
            {preview.spec && (
              <span>
                {' '}
                · IS spec {preview.spec[0]}–{preview.spec[1]} · {preview.within ? 'Within band ✓' : 'OUT OF SPEC'}
              </span>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
