import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeftRight, Database, Droplets, Gauge, Plus, Ruler } from 'lucide-react';
import { apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { DeleteRecordButton, ExportCsvButton } from '../components/OwnerActions';
import { useIdempotencyTracker } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDateTime, formatINR, formatLitres, formatNumber } from '../lib/format';
import {
  Badge,
  TableScrollHint,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingBlock,
  MeterBar,
  Modal,
  PageHeader,
  Pagination,
  Select,
  StatCard,
  StatusBadge,
  Tabs,
  Textarea,
  useToast,
} from '../components/ui';

interface Tank {
  id: string;
  code: string;
  name: string;
  productId: string;
  productName: string;
  productColor: string;
  capacityLitres: string;
  deadStockLitres: string;
  bookStockLitres: string;
  fillPct: string;
  lastMeasuredLitres: string | null;
  lastVarianceLitres: string | null;
  lastMeasuredAt: string | null;
  lastWaterDipCm: string | null;
  calibrationReference: string | null;
  status: string;
  stationId: string;
}

interface StockOverview {
  tanks: Tank[];
  products: { productId: string; name: string; code: string; color: string; bookStockLitres: string }[];
  warnings: { level: string; message: string; tankId?: string }[];
  recentMovements: Movement[];
}

interface Movement {
  id: string;
  type: string;
  quantityLitres: string;
  occurredAt: string;
  note: string | null;
  tankCode: string;
  tankName: string;
  productName: string;
  referenceType: string | null;
  referenceId: string | null;
}

interface Product { id: string; name: string; code: string; isActive: number }

interface Ledger {
  tank: { id: string; code: string; capacityLitres: string };
  closingBookStock: string;
  ledger: (Movement & { signedLitres: string; runningLitres: string })[];
}

export default function Tanks() {
  const { can } = useAuth();
  const { stationId, station } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState('tanks');
  const [createOpen, setCreateOpen] = useState(false);
  const [editTank, setEditTank] = useState<Tank | null>(null);
  const [dipTank, setDipTank] = useState<Tank | null>(null);
  const [adjustTank, setAdjustTank] = useState<Tank | null>(null);
  const [openingTank, setOpeningTank] = useState<Tank | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);
  const [ledgerTank, setLedgerTank] = useState<Tank | null>(null);
  const [page, setPage] = useState(1);

  const overview = useQuery({
    queryKey: ['stock', stationId],
    queryFn: () => apiGet<StockOverview>(`/inventory/stock?stationId=${stationId}`),
    enabled: Boolean(stationId) && can('stock.read'),
    refetchInterval: 60_000,
  });

  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => apiGet<Product[]>('/catalog/products?pageSize=50'),
    enabled: Boolean(stationId),
  });

  const movements = useQuery({
    queryKey: ['stock-movements', stationId, page],
    queryFn: () => apiList<Movement>(`/inventory/movements?stationId=${stationId}&page=${page}&pageSize=15`),
    enabled: Boolean(stationId) && can('stock.read'),
  });

  const ledger = useQuery({
    queryKey: ['tank-ledger', ledgerTank?.id],
    queryFn: () => apiGet<Ledger>(`/inventory/tanks/${ledgerTank!.id}/ledger`),
    enabled: Boolean(ledgerTank),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['stock'] });
    void queryClient.invalidateQueries({ queryKey: ['stock-movements'] });
    void queryClient.invalidateQueries({ queryKey: ['tank-ledger'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const createTank = useMutation({
    mutationFn: (payload: Record<string, unknown>) => apiPost('/catalog/tanks', payload),
    onSuccess: () => { toast('Tank created'); setCreateOpen(false); invalidate(); },
    onError: (e) => toast('Could not create tank', { tone: 'danger', description: (e as Error).message }),
  });

  const updateTank = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: Record<string, unknown> }) => apiPatch(`/catalog/tanks/${id}`, payload),
    onSuccess: () => { toast('Tank updated'); setEditTank(null); invalidate(); },
    onError: (e) => toast('Update failed', { tone: 'danger', description: (e as Error).message }),
  });

  const dipKeys = useIdempotencyTracker('tank-dip');
  const recordDip = useMutation({
    mutationFn: (payload: Record<string, unknown>) => apiPost(`/catalog/tanks/${dipTank!.id}/measurements`, payload, dipKeys.keyFor({ id: dipTank!.id, payload })),
    onSuccess: () => { dipKeys.reset(); toast('Physical dip recorded', { description: 'Book vs measured variance stored separately.' }); setDipTank(null); invalidate(); },
    onError: (e) => toast('Could not record dip', { tone: 'danger', description: (e as Error).message }),
  });

  const adjustmentKeys = useIdempotencyTracker('stock-adjustment');
  const adjustment = useMutation({
    mutationFn: (payload: Record<string, unknown>) => apiPost('/inventory/adjustments', payload, adjustmentKeys.keyFor(payload)),
    onSuccess: () => { adjustmentKeys.reset(); toast('Stock adjusted'); setAdjustTank(null); invalidate(); },
    onError: (e) => toast('Adjustment failed', { tone: 'danger', description: (e as Error).message }),
  });

  const openingKeys = useIdempotencyTracker('opening-stock');
  const opening = useMutation({
    mutationFn: (payload: Record<string, unknown>) => apiPost('/inventory/opening-stock', payload, openingKeys.keyFor(payload)),
    onSuccess: () => { openingKeys.reset(); toast('Opening stock saved'); setOpeningTank(null); invalidate(); },
    onError: (e) => toast('Could not set opening stock', { tone: 'danger', description: (e as Error).message }),
  });

  const transferKeys = useIdempotencyTracker('stock-transfer');
  const transfer = useMutation({
    mutationFn: (payload: Record<string, unknown>) => apiPost('/inventory/transfers', payload, transferKeys.keyFor(payload)),
    onSuccess: () => { transferKeys.reset(); toast('Stock transferred', { description: 'Two ledger entries created (out/in).' }); setTransferOpen(false); invalidate(); },
    onError: (e) => toast('Transfer failed', { tone: 'danger', description: (e as Error).message }),
  });

  if (!stationId) return <EmptyState title="No station assigned" />;
  if (!can('stock.read')) return <EmptyState title="Stock access required" description="Ask your organization owner for stock access." />;

  const data = overview.data;
  const tanks = data?.tanks ?? [];
  const totalBook = tanks.reduce((acc, t) => acc + Number(t.bookStockLitres), 0);
  const totalCapacity = tanks.reduce((acc, t) => acc + Number(t.capacityLitres), 0);
  const movementsRows = movements.data?.data ?? [];
  const meta: ListMeta = movements.data?.meta ?? { page: 1, pageSize: 15, total: 0, totalPages: 1 };

  return (
    <>
      <PageHeader
        title="Tanks & fuel stock"
        description={`${station?.name ?? ''} · book stock from the movement ledger, physical dips recorded separately`}
        actions={
          <>
            <ExportCsvButton path={`/catalog/tanks?stationId=${stationId}`} filename="petroflow-tanks" />
            {can('stock.write') && (
              <>
                <Button size="sm" variant="secondary" icon={<ArrowLeftRight className="h-4 w-4" />} onClick={() => setTransferOpen(true)}>Transfer</Button>
                <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>New tank</Button>
              </>
            )}
          </>
        }
      />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Tanks" value={tanks.length} hint={`${tanks.filter((t) => t.status === 'ACTIVE').length} active`} icon={<Database className="h-4 w-4" />} />
        <StatCard label="Book stock" value={formatLitres(totalBook)} hint="Sum of signed stock movements" icon={<Droplets className="h-4 w-4" />} tone="accent" />
        <StatCard label="Capacity" value={formatLitres(totalCapacity)} hint={totalCapacity > 0 ? `${formatNumber((totalBook / totalCapacity) * 100, 1)}% utilised` : '—'} icon={<Gauge className="h-4 w-4" />} />
        <StatCard label="Stock warnings" value={data?.warnings.length ?? 0} hint="Negative book stock, low levels or dip variance" tone={(data?.warnings.length ?? 0) > 0 ? 'warning' : 'success'} />
      </div>

      {(data?.warnings.length ?? 0) > 0 && (
        <div className="mt-4 space-y-2">
          {data!.warnings.map((warning, index) => (
            <div
              key={index}
              className={warning.level === 'error' ? 'callout-danger' : 'callout-warn'}
            >
              {warning.message}
            </div>
          ))}
        </div>
      )}

      <div className="mt-4">
        <Tabs
          tabs={[
            { key: 'tanks', label: 'Tank levels', count: tanks.length },
            { key: 'movements', label: 'Stock ledger', count: meta.total },
          ]}
          active={tab}
          onChange={setTab}
        />
      </div>

      {tab === 'tanks' ? (
        <>
          <div className="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {overview.isLoading ? (
              Array.from({ length: 3 }).map((_, i) => <Card key={i}><LoadingBlock rows={3} /></Card>)
            ) : overview.isError ? (
              <div className="md:col-span-2 xl:col-span-3"><ErrorState error={overview.error} onRetry={() => void overview.refetch()} /></div>
            ) : tanks.length === 0 ? (
              <div className="md:col-span-2 xl:col-span-3">
                <Card><EmptyState title="No tanks configured" description="Create tanks to track fuel inventory and dips." action={can('tanks.write') ? <Button size="sm" variant="primary" onClick={() => setCreateOpen(true)}>New tank</Button> : undefined} /></Card>
              </div>
            ) : (
              tanks.map((tank) => {
                const fill = Math.max(0, Math.min(100, Number(tank.fillPct)));
                const ullage = Math.max(0, Number(tank.capacityLitres) - Number(tank.bookStockLitres));
                const isLow = fill < 15;
                const isWatch = !isLow && fill < 25;
                const liquidColor = isLow
                  ? 'rgb(var(--danger))'
                  : isWatch
                    ? 'rgb(var(--warn))'
                    : tank.productColor || 'rgb(var(--accent))';
                return (
                <Card key={tank.id} className="relative overflow-hidden p-5">
                  <span
                    aria-hidden
                    className="absolute inset-x-0 top-0 h-[2.5px] opacity-85"
                    style={{ background: liquidColor }}
                  />
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="rounded-md border border-carbon-700 bg-carbon-800 px-2 py-0.5 font-mono text-[11px] font-bold text-ink-200">
                          {tank.code}
                        </span>
                        <p className="font-display text-sm font-bold text-ink-100">{tank.name}</p>
                      </div>
                      <p className="mt-1 flex items-center gap-1.5 text-xs font-medium text-ink-400">
                        <span className="h-2.5 w-2.5 rounded-full" style={{ background: tank.productColor }} /> {tank.productName}
                      </p>
                    </div>
                    <StatusBadge status={tank.status} />
                  </div>

                  <div className="mt-4 flex items-center gap-3.5 rounded-2xl border border-carbon-700/75 bg-carbon-800/45 p-3.5">
                    <div
                      aria-hidden
                      className="relative h-16 w-10 shrink-0 overflow-hidden rounded-xl border border-carbon-600/80 bg-inset shadow-inner"
                    >
                      <div
                        className="absolute inset-x-0 bottom-0 transition-all duration-500"
                        style={{ height: `${Math.max(6, fill)}%`, backgroundColor: liquidColor, opacity: 0.85 }}
                      >
                        <div className="h-[2px] w-full bg-white/45" />
                      </div>
                      <span className="relative z-[1] grid h-full place-items-center font-mono text-[10px] font-extrabold text-ink-100 drop-shadow-[0_1px_2px_rgb(0_0_0/0.35)]">
                        {formatNumber(fill, 0)}%
                      </span>
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="mb-2 flex items-end justify-between gap-2">
                        <div>
                          <p className="num font-display text-2xl font-extrabold leading-none text-ink-100">
                            {formatNumber(tank.bookStockLitres, 0)}{' '}
                            <span className="font-sans text-xs font-medium text-ink-500">L</span>
                          </p>
                          <p className="mt-1 text-[11px] text-ink-500">
                            of {formatNumber(tank.capacityLitres, 0)} L · Ullage {formatNumber(ullage, 0)} L
                          </p>
                        </div>
                        <Badge tone={isLow ? 'danger' : isWatch ? 'warning' : 'success'} dot>
                          {isLow ? 'Low' : isWatch ? 'Reorder' : `${formatNumber(fill, 0)}%`}
                        </Badge>
                      </div>
                      <MeterBar
                        value={fill}
                        tone={isLow ? 'danger' : isWatch ? 'warn' : 'accent'}
                        color={!isLow && !isWatch ? tank.productColor : undefined}
                        label={`${tank.code} fill level`}
                      />
                    </div>
                  </div>

                  <dl className="mt-3.5 grid grid-cols-2 gap-2.5 rounded-xl border border-carbon-700/55 bg-carbon-900/45 p-3 text-[11px]">
                    <div>
                      <dt className="text-ink-500">Last physical dip</dt>
                      <dd className="num mt-0.5 font-semibold text-ink-200">{tank.lastMeasuredLitres ? formatLitres(tank.lastMeasuredLitres) : 'No dip recorded'}</dd>
                    </div>
                    <div>
                      <dt className="text-ink-500">Dip vs book variance</dt>
                      <dd className={`num mt-0.5 font-semibold ${tank.lastVarianceLitres && Math.abs(Number(tank.lastVarianceLitres)) > 50 ? 'text-warn' : 'text-ink-200'}`}>
                        {tank.lastVarianceLitres ? `${formatNumber(tank.lastVarianceLitres, 3)} L` : '—'}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-ink-500">Dead stock</dt>
                      <dd className="num mt-0.5 text-ink-300">{formatLitres(tank.deadStockLitres)}</dd>
                    </div>
                    <div>
                      <dt className="text-ink-500">Measured at</dt>
                      <dd className="mt-0.5 text-ink-300">{tank.lastMeasuredAt ? formatDateTime(tank.lastMeasuredAt) : '—'}</dd>
                    </div>
                    <div className="col-span-2 border-t border-carbon-700/50 pt-2">
                      <dt className="text-ink-500">Water bottom cut (paste)</dt>
                      <dd className={tank.lastWaterDipCm && Number(tank.lastWaterDipCm) > 2 ? 'mt-0.5 font-semibold text-danger' : 'mt-0.5 text-ink-300'}>
                        {tank.lastWaterDipCm ? `${formatNumber(tank.lastWaterDipCm, 1)} cm${Number(tank.lastWaterDipCm) > 2 ? ' — High water level (>2 cm)' : ''}` : '—'}
                      </dd>
                    </div>
                  </dl>

                  <div className="mt-4 flex flex-wrap gap-1.5 border-t border-carbon-700/70 pt-3.5">
                    {can('stock.write') && (
                      <>
                        <Button size="sm" variant="secondary" icon={<Ruler className="h-3.5 w-3.5" />} onClick={() => setDipTank(tank)}>Record dip</Button>
                        <Button size="sm" variant="ghost" onClick={() => setAdjustTank(tank)}>Adjust</Button>
                        {can('tanks.write') && <Button size="sm" variant="ghost" onClick={() => setOpeningTank(tank)}>Opening stock</Button>}
                      </>
                    )}
                    <Button size="sm" variant="ghost" onClick={() => setLedgerTank(tank)}>Ledger</Button>
                    {can('tanks.write') && <Button size="sm" variant="ghost" onClick={() => setEditTank(tank)}>Edit</Button>}
                    <DeleteRecordButton path={`/catalog/tanks/${tank.id}`} label={tank.name} onDeleted={invalidate} />
                  </div>
                </Card>
                );
              })
            )}
          </div>

          {can('reports.read') && <IndentCard stationId={stationId} />}

          <Card className="mt-3">
            <CardHeader title="Book stock by product" subtitle="Derived from tank movement ledgers" />
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>Product</th><th>Code</th><th className="text-right">Book stock</th></tr></thead>
                <tbody>
                  {(data?.products ?? []).map((product) => (
                    <tr key={product.productId}>
                      <td className="flex items-center gap-2 text-ink-100"><span className="h-2 w-2 rounded-full" style={{ background: product.color }} />{product.name}</td>
                      <td><Badge tone="muted">{product.code}</Badge></td>
                      <td className="num text-right">{formatLitres(product.bookStockLitres)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      ) : (
        <Card className="mt-3">
          <CardHeader title="Stock movement ledger" subtitle="Every receipt, dispensation and adjustment is immutable" />
          {movements.isLoading ? (
            <LoadingBlock rows={5} />
          ) : movements.isError ? (
            <div className="p-4"><ErrorState error={movements.error} onRetry={() => void movements.refetch()} /></div>
          ) : movementsRows.length === 0 ? (
            <EmptyState title="No stock movements" description="Record an opening stock or a delivery to begin the ledger." />
          ) : (
            <>
              <div className="table-wrap">
                <table className="data-table">
                  <thead><tr><th>Date</th><th>Tank</th><th>Product</th><th>Type</th><th className="text-right">Quantity</th><th>Note</th></tr></thead>
                  <tbody>
                    {movementsRows.map((movement) => {
                      const signed =
                        movement.type === 'ADJUSTMENT'
                          ? Number(movement.quantityLitres) >= 0
                          : ['OPENING', 'DELIVERY', 'TRANSFER_IN'].includes(movement.type);
                      const absQty = Math.abs(Number(movement.quantityLitres));
                      return (
                        <tr key={movement.id}>
                          <td className="text-xs text-ink-400">{formatDateTime(movement.occurredAt)}</td>
                          <td className="text-ink-200">{movement.tankCode}</td>
                          <td className="text-xs text-ink-400">{movement.productName}</td>
                          <td><Badge tone={movement.type === 'DISPENSED' ? 'accent' : movement.type === 'DELIVERY' ? 'success' : 'neutral'}>{movement.type}</Badge></td>
                          <td className={`num text-right ${signed ? 'text-ok' : 'text-warn'}`}>{signed ? '+' : '−'}{formatNumber(absQty, 3)} L</td>
                          <td className="max-w-[320px] text-[11px] text-ink-500">{movement.note ?? '—'}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <Pagination {...meta} onPage={setPage} />
            </>
          )}
        </Card>
      )}

      <TankFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        stationId={stationId}
        products={products.data ?? []}
        loading={createTank.isPending}
        onSubmit={(payload) => createTank.mutate(payload)}
      />

      {editTank && (
        <TankFormModal
          open
          onClose={() => setEditTank(null)}
          stationId={stationId}
          products={products.data ?? []}
          tank={editTank}
          loading={updateTank.isPending}
          onSubmit={(payload) => updateTank.mutate({ id: editTank.id, payload: { ...payload, code: undefined, stationId: undefined, productId: undefined } as Record<string, unknown> })}
        />
      )}

      {dipTank && <DipModal tank={dipTank} onClose={() => setDipTank(null)} loading={recordDip.isPending} onSubmit={(payload) => recordDip.mutate(payload)} />}
      {adjustTank && <AdjustmentModal tank={adjustTank} onClose={() => setAdjustTank(null)} loading={adjustment.isPending} onSubmit={(payload) => adjustment.mutate(payload)} />}
      {openingTank && <OpeningModal tank={openingTank} onClose={() => setOpeningTank(null)} loading={opening.isPending} onSubmit={(payload) => opening.mutate(payload)} />}
      {transferOpen && <TransferModal tanks={tanks} onClose={() => setTransferOpen(false)} loading={transfer.isPending} onSubmit={(payload) => transfer.mutate(payload)} />}

      <Modal
        open={Boolean(ledgerTank)}
        onClose={() => setLedgerTank(null)}
        title={ledgerTank ? `${ledgerTank.code} · stock ledger` : 'Stock ledger'}
        description={ledger.data ? `Closing book stock ${formatLitres(ledger.data.closingBookStock)}` : undefined}
        size="xl"
        footer={<Button size="sm" variant="ghost" onClick={() => setLedgerTank(null)}>Close</Button>}
      >
        {ledger.isLoading ? (
          <LoadingBlock rows={4} />
        ) : ledger.isError ? (
          <ErrorState error={ledger.error} onRetry={() => void ledger.refetch()} />
        ) : ledger.data ? (
          <div>
          <TableScrollHint className="rounded-t-lg border border-b-0 border-carbon-700/60" />
          <div className="table-wrap rounded-b-lg border border-t-0 border-carbon-700/60">
            <table className="data-table min-w-[720px]">
              <thead><tr><th>Date</th><th>Type</th><th className="text-right">Signed</th><th className="text-right">Running</th><th>Note</th></tr></thead>
              <tbody>
                {ledger.data.ledger.map((row) => (
                  <tr key={row.id}>
                    <td className="text-xs text-ink-400">{formatDateTime(row.occurredAt)}</td>
                    <td><Badge tone={Number(row.signedLitres) >= 0 ? 'success' : 'warning'}>{row.type}</Badge></td>
                    <td className={`num text-right ${Number(row.signedLitres) >= 0 ? 'text-ok' : 'text-warn'}`}>{formatNumber(row.signedLitres, 3)} L</td>
                    <td className="num text-right">{formatNumber(row.runningLitres, 3)} L</td>
                    <td className="max-w-[280px] text-[11px] text-ink-500">{row.note ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          </div>
        ) : null}
      </Modal>

      <div className="mt-4">
        <Card className="p-4">
          <p className="text-xs font-medium text-ink-200">Stock accounting note</p>
          <p className="mt-1 text-[11px] leading-relaxed text-ink-500">
            Closing book stock = opening stock + received quantity − net dispensed quantity + other validated movements.
            Nozzle totalizers alone cannot determine exact tank inventory; physical dips are stored separately and compared
            against book stock. All monetary values are displayed in INR and exclude taxes that were not captured on the
            purchase invoice.
          </p>
        </Card>
      </div>
    </>
  );
}

function TankFormModal({
  open,
  onClose,
  stationId,
  products,
  tank,
  loading,
  onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  stationId: string;
  products: Product[];
  tank?: Tank;
  loading: boolean;
  onSubmit: (payload: Record<string, unknown>) => void;
}) {
  const [form, setForm] = useState({
    code: tank?.code ?? '',
    name: tank?.name ?? '',
    productId: tank?.productId ?? products[0]?.id ?? '',
    capacityLitres: tank?.capacityLitres ?? '20000',
    deadStockLitres: tank?.deadStockLitres ?? '500',
    calibrationReference: tank?.calibrationReference ?? '',
    status: tank?.status ?? 'ACTIVE',
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={tank ? `Edit tank ${tank.code}` : 'New tank'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={!form.code || !form.name || !form.productId} onClick={() => onSubmit({ ...form, stationId })}>
            {tank ? 'Save tank' : 'Create tank'}
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Tank code" required><Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} disabled={Boolean(tank)} /></Field>
        <Field label="Tank name" required><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
        <Field label="Fuel product" required>
          <Select value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })} disabled={Boolean(tank)}>
            {products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </Field>
        <Field label="Capacity (litres)" required><Input inputMode="decimal" value={form.capacityLitres} onChange={(e) => setForm({ ...form, capacityLitres: e.target.value })} /></Field>
        <Field label="Dead stock (litres)"><Input inputMode="decimal" value={form.deadStockLitres} onChange={(e) => setForm({ ...form, deadStockLitres: e.target.value })} /></Field>
        <Field label="Calibration reference"><Input value={form.calibrationReference} onChange={(e) => setForm({ ...form, calibrationReference: e.target.value })} placeholder="Dip chart revision" /></Field>
        {tank && (
          <Field label="Status">
            <Select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
              <option value="ACTIVE">ACTIVE</option>
              <option value="INACTIVE">INACTIVE</option>
            </Select>
          </Field>
        )}
      </div>
    </Modal>
  );
}

interface IndentRow {
  tankId: string;
  tankCode: string;
  productName: string;
  bookStockLitres: string;
  capacityLitres: string;
  fillPct: string;
  ullageLitres: string;
  avgDailySalesLitres: string;
  daysOfCover: string | null;
  suggestedOrderLitres: string;
  priority: string;
}

function IndentCard({ stationId }: { stationId: string }) {
  const indent = useQuery<{ rows: IndentRow[]; basedOnDays: number }>({
    queryKey: ['indent', stationId],
    queryFn: () => apiGet(`/reports/indent?stationId=${stationId}`),
    enabled: Boolean(stationId),
  });
  const rows = indent.data?.rows ?? [];
  const priorityNote = (row: IndentRow) => {
    if (row.priority === 'ORDER_NOW') return { tone: 'danger' as const, text: 'Order immediately' };
    if (row.priority === 'ORDER_SOON') return { tone: 'warning' as const, text: 'Order within 2–3 days' };
    return { tone: 'success' as const, text: 'Adequate cover' };
  };
  if (indent.isLoading) return <Card className="mt-4"><LoadingBlock rows={2} /></Card>;
  if (indent.isError || rows.length === 0) return null;
  return (
    <Card className="mt-4">
      <CardHeader
        title="Tanker indent & reorder forecast"
        subtitle={`Based on ${indent.data?.basedOnDays ?? 14}-day average daily dispensed volume and safe tank ullage capacity.`}
      />
      <TableScrollHint />
      <div className="table-wrap">
        <table className="data-table min-w-[680px]">
          <thead>
            <tr>
              <th>Tank</th>
              <th>Product</th>
              <th className="text-right">Book stock (L)</th>
              <th className="text-right">Avg daily sale (L)</th>
              <th className="text-right">Days of cover</th>
              <th className="text-right">Suggested indent (L)</th>
              <th>Recommendation</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const days = row.daysOfCover === null ? null : Number(row.daysOfCover);
              const tip = priorityNote(row);
              return (
                <tr key={row.tankId}>
                  <td className="font-semibold text-ink-100">{row.tankCode}</td>
                  <td className="text-xs">{row.productName}</td>
                  <td className="num text-right">{formatNumber(row.bookStockLitres, 0)}<span className="block text-[10px] text-ink-500">{formatNumber(row.fillPct, 0)}% full · ullage {formatNumber(row.ullageLitres, 0)} L</span></td>
                  <td className="num text-right">{formatNumber(row.avgDailySalesLitres, 0)}</td>
                  <td className={`num text-right ${days !== null && days < 2 ? 'font-bold text-danger' : days !== null && days < 4 ? 'font-semibold text-warn' : 'text-ink-300'}`}>
                    {days === null ? '—' : `${formatNumber(days, 1)} days`}
                  </td>
                  <td className="num text-right font-semibold text-ink-100">{formatNumber(row.suggestedOrderLitres, 0)}</td>
                  <td><Badge tone={tip.tone} dot>{tip.text}</Badge></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function DipModal({ tank, onClose, loading, onSubmit }: { tank: Tank; onClose: () => void; loading: boolean; onSubmit: (payload: Record<string, unknown>) => void }) {
  const [dipCm, setDipCm] = useState('');
  const [measuredLitres, setMeasuredLitres] = useState('');
  const [waterDipCm, setWaterDipCm] = useState('');
  const [note, setNote] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title={`Physical dip · ${tank.code}`}
      description={`Book stock ${formatLitres(tank.bookStockLitres)} · capacity ${formatLitres(tank.capacityLitres)}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={!dipCm && !measuredLitres} onClick={() => onSubmit({ dipCm: dipCm || undefined, measuredLitres: measuredLitres || undefined, waterDipCm: waterDipCm || undefined, note: note || undefined, method: dipCm ? 'DIP' : 'MANUAL' })}>
            Record dip
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Dip reading (cm)" hint="Converted using the tank calibration table"><Input inputMode="decimal" value={dipCm} onChange={(e) => setDipCm(e.target.value)} /></Field>
        <Field label="Or measured litres" hint="Directly measured volume"><Input inputMode="decimal" value={measuredLitres} onChange={(e) => setMeasuredLitres(e.target.value)} /></Field>
        <Field label="Water dip (cm)" hint="Water-finding paste reading at tank bottom. Above 2 cm requires immediate dewatering.">
          <Input inputMode="decimal" value={waterDipCm} onChange={(e) => setWaterDipCm(e.target.value)} placeholder="0" />
        </Field>
        <Field label="Note" className="sm:col-span-2"><Textarea value={note} onChange={(e) => setNote(e.target.value)} placeholder="Dip taken by, time, conditions" /></Field>
      </div>
    </Modal>
  );
}

function AdjustmentModal({ tank, onClose, loading, onSubmit }: { tank: Tank; onClose: () => void; loading: boolean; onSubmit: (payload: Record<string, unknown>) => void }) {
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title={`Stock adjustment · ${tank.code}`}
      description="Signed adjustment (positive adds stock, negative removes). Audited with reason."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={!quantity || reason.trim().length < 5} onClick={() => onSubmit({ tankId: tank.id, quantityLitres: quantity, reason })}>Post adjustment</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Quantity (litres)" required hint="e.g. -12.5 to write off a measured loss"><Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>
        <Field label="Reason" required><Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Dip variance approval, evaporation, calibration correction…" /></Field>
      </div>
    </Modal>
  );
}

function OpeningModal({ tank, onClose, loading, onSubmit }: { tank: Tank; onClose: () => void; loading: boolean; onSubmit: (payload: Record<string, unknown>) => void }) {
  const [litres, setLitres] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title={`Opening stock · ${tank.code}`}
      description="Only valid before any stock movement. Otherwise use an adjustment."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={!litres} onClick={() => onSubmit({ tankId: tank.id, litres })}>Set opening stock</Button>
        </>
      }
    >
      <Field label="Opening stock (litres)" required><Input inputMode="decimal" value={litres} onChange={(e) => setLitres(e.target.value)} /></Field>
    </Modal>
  );
}

function TransferModal({ tanks, onClose, loading, onSubmit }: { tanks: Tank[]; onClose: () => void; loading: boolean; onSubmit: (payload: Record<string, unknown>) => void }) {
  const [fromTankId, setFrom] = useState(tanks[0]?.id ?? '');
  const [toTankId, setTo] = useState(tanks[1]?.id ?? '');
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title="Transfer stock between tanks"
      description="Both tanks must hold the same product. Two ledger entries are created."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={!quantity || reason.trim().length < 5 || fromTankId === toTankId} onClick={() => onSubmit({ fromTankId, toTankId, quantityLitres: quantity, reason })}>Transfer stock</Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="From tank"><Select value={fromTankId} onChange={(e) => setFrom(e.target.value)}>{tanks.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.productName}</option>)}</Select></Field>
        <Field label="To tank"><Select value={toTankId} onChange={(e) => setTo(e.target.value)}>{tanks.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.productName}</option>)}</Select></Field>
        <Field label="Quantity (litres)" required><Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} /></Field>
        <Field label="Reason" required><Input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
      </div>
    </Modal>
  );
}
