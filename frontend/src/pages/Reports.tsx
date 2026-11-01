import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Download, Printer, ReceiptText, Share2 } from 'lucide-react';
import { apiBlob, apiDownload, apiGet } from '../lib/api';
import { shareReport } from '../lib/share';
import { useAuth, useStations } from '../lib/auth';
import { formatDate, formatDateTime, formatINR, formatNumber, todayIst } from '../lib/format';
import { downloadCsv } from '../lib/utils';
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
  PageHeader,
  StatCard,
  Select,
  StatusBadge,
  useToast,
} from '../components/ui';

interface Dsr {
  station: { name: string; code: string; address: string | null; city: string | null; state: string | null };
  date: string;
  generatedAt: string;
  nozzleSales: Record<string, unknown>[];
  productTotals: { product: string; code: string; litres: string; value: string }[];
  shifts: Record<string, unknown>[];
  transactions: Record<string, unknown>[];
  paymentMix: Record<string, string>;
  totals: {
    aggregateLitres: string;
    aggregateEstimatedValue: string;
    transactionLitres: string;
    transactionValue: string;
    expenses: string;
    receipts: string;
    purchases: string;
  };
  expenses: Record<string, unknown>[];
  deliveries: Record<string, unknown>[];
  receipts: Record<string, unknown>[];
  stock: Record<string, unknown>[];
  reconciliation: Record<string, unknown> | null;
  notes: string[];
}

// Merge independently authorized station DSRs; integer minor units avoid floating-point drift.
function mergeDsrs(reports: Dsr[]): Dsr {
  const sum = (values: (string | number)[], digits: number) => (values.reduce<number>((a, v) => a + Math.round(Number(v) * 10 ** digits), 0) / 10 ** digits).toFixed(digits);
  const productMap = new Map<string, Dsr['productTotals'][number]>();
  for (const report of reports) for (const row of report.productTotals) {
    const previous = productMap.get(row.code);
    productMap.set(row.code, { ...row, litres: sum([previous?.litres ?? '0', row.litres], 3), value: sum([previous?.value ?? '0', row.value], 2) });
  }
  const keys = Object.keys(reports[0].totals) as (keyof Dsr['totals'])[];
  const totals = Object.fromEntries(keys.map((key) => [key, sum(reports.map((r) => r.totals[key]), key.endsWith('Litres') ? 3 : 2)])) as unknown as Dsr['totals'];
  const modes = new Set(reports.flatMap((r) => Object.keys(r.paymentMix)));
  const tagged = (key: 'nozzleSales' | 'shifts' | 'transactions' | 'expenses' | 'deliveries' | 'receipts' | 'stock') => reports.flatMap((r) => r[key].map((row) => ({ ...row, stationName: r.station.name })));
  return { ...reports[0], station: { name: 'Dono Pump · Combined', code: 'ALL', address: null, city: null, state: null },
    totals, productTotals: [...productMap.values()], paymentMix: Object.fromEntries([...modes].map((mode) => [mode, sum(reports.map((r) => r.paymentMix[mode] ?? '0'), 2)])),
    nozzleSales: tagged('nozzleSales'), shifts: tagged('shifts'), transactions: tagged('transactions'), expenses: tagged('expenses'), deliveries: tagged('deliveries'), receipts: tagged('receipts'), stock: tagged('stock'),
    reconciliation: null, notes: [...reports[0].notes, 'Combined reconciliation is not shown; select a pump to view its reconciliation.'] };
}

export default function Reports() {
  const { stationId, station, stations } = useStations();
  const { me } = useAuth();
  const { toast } = useToast();
  const [scope, setScope] = useState<string>('current');
  const selected = scope === 'current' ? stationId : scope;
  const [date, setDate] = useState(todayIst());

  const dsr = useQuery({
    queryKey: ['dsr', selected, date, stations.map((s) => s.id).join(',')],
    queryFn: async () => selected === 'all'
      ? mergeDsrs(await Promise.all(stations.map((s) => apiGet<Dsr>(`/reports/dsr?stationId=${s.id}&date=${date}`))))
      : apiGet<Dsr>(`/reports/dsr?stationId=${selected}&date=${date}`),
    enabled: Boolean(stationId),
  });

  if (!stationId) return <EmptyState title="No station assigned" />;

  return (
    <>
      <PageHeader
        title="Daily Sales Report (DSR)"
        description={`${selected === 'all' ? 'Dono Pump' : stations.find((s) => s.id === selected)?.name ?? station?.name ?? ''} · operational report for the business date`}
        actions={
          <>
            {me?.user.roleKey === 'OWNER' && stations.length > 1 && <Field label="Pump" className="mb-0"><Select value={selected} onChange={(e) => setScope(e.target.value)} aria-label="Report pump"><option value="all">Dono Pump</option>{stations.map((s, i) => <option key={s.id} value={s.id}>Pump {i + 1} · {s.name}</option>)}</Select></Field>}
            <Field label="Business date" className="mb-0">
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="w-auto" />
            </Field>
            <Button size="sm" variant="ghost" icon={<Printer className="h-3.5 w-3.5" />} onClick={() => window.print()} className="mt-4">
              Print
            </Button>
            <Button
              size="sm"
              variant="ghost"
              icon={<Share2 className="h-3.5 w-3.5" />}
              className="mt-4"
              disabled={!dsr.data}
              onClick={() => {
                const report = dsr.data;
                if (!report) return;
                const rows = nozzleRows(report);
                void shareReport({
                  title: `DSR ${date} · ${report.station.name}`,
                  filename: `dsr-${date}.csv`,
                  text: `${report.station.name} · ${date}\nLitres ${report.totals.aggregateLitres}\nEstimated sales ₹${report.totals.aggregateEstimatedValue}\nExpenses ₹${report.totals.expenses}`,
                  rows,
                }).then((mode) => toast(mode === 'whatsapp' ? 'Opened WhatsApp' : 'Report shared', { description: mode === 'whatsapp' ? 'Summary copied. Attach the CSV if you also downloaded it.' : undefined }));
              }}
            >
              Share
            </Button>
            <Button
              size="sm"
              variant="secondary"
              icon={<Download className="h-3.5 w-3.5" />}
              className="mt-4"
              disabled={!dsr.data}
              onClick={() => downloadCsv(`dsr-${date}.csv`, nozzleRows(dsr.data!))}
            >
              Download CSV
            </Button>
          </>
        }
      />

      {dsr.isLoading ? (
        <LoadingBlock rows={6} />
      ) : dsr.isError ? (
        <ErrorState error={dsr.error} onRetry={() => void dsr.refetch()} />
      ) : dsr.data ? (
        <div className="space-y-5">
          <Card className="relative overflow-hidden print:border-0">
            <span aria-hidden className="brand-gradient absolute inset-x-0 top-0 h-[2.5px] opacity-90 print:hidden" />
            <div className="hero-mesh flex flex-wrap items-start justify-between gap-3 border-b border-carbon-700/60 p-5">
              <div>
                <div className="flex items-center gap-2">
                  <span className="rounded-md border border-accent/25 bg-accent/10 px-2 py-0.5 font-mono text-[10.5px] font-bold text-accent">
                    {dsr.data.station.code}
                  </span>
                  <p className="font-display text-lg font-extrabold tracking-tight text-ink-100">{dsr.data.station.name}</p>
                </div>
                <p className="mt-1 text-xs text-ink-500">
                  {dsr.data.station.address ? `${dsr.data.station.address}` : 'Operational DSR Statement'}
                  {dsr.data.station.city ? `, ${dsr.data.station.city}` : ''}
                </p>
                <p className="mt-1 text-xs font-medium text-ink-400">Business date: {formatDate(dsr.data.date)} (Asia/Kolkata)</p>
              </div>
              <div className="text-right text-[11px] text-ink-500">
                <p>Generated {formatDateTime(dsr.data.generatedAt)}</p>
                <p className="font-medium text-ink-400">PetroFlow ERP · INR</p>
                <div className="mt-1.5 flex justify-end gap-1.5">
                  <Badge tone="accent">{dsr.data.shifts.length} shifts</Badge>
                  <Badge tone="neutral">{dsr.data.transactions.length} transactions</Badge>
                </div>
              </div>
            </div>
            <div className="grid gap-4 p-5 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard label="Aggregate litres" value={formatNumber(dsr.data.totals.aggregateLitres, 3)} hint="Closed shift totalizers" />
              <StatCard label="Estimated sales value" value={formatINR(dsr.data.totals.aggregateEstimatedValue, true)} hint="Aggregate estimate" tone="accent" />
              <StatCard label="Recorded transactions" value={formatINR(dsr.data.totals.transactionValue, true)} hint={`${formatNumber(dsr.data.totals.transactionLitres, 3)} L`} />
              <StatCard label="Expenses" value={formatINR(dsr.data.totals.expenses, true)} hint={`Purchases ${formatINR(dsr.data.totals.purchases, true)}`} />
            </div>
          </Card>

          <div className="grid gap-5 lg:grid-cols-2">
            <Card>
              <CardHeader title="Payment mix (recorded)" subtitle="Only transaction-level sales carry a payment mode" />
              <div className="divide-y divide-carbon-800/80">
                {Object.keys(dsr.data.paymentMix).length === 0 ? (
                  <p className="px-4 py-6 text-center text-xs text-ink-500">No transaction-level payments recorded.</p>
                ) : (
                  Object.entries(dsr.data.paymentMix).map(([mode, amount]) => (
                    <div key={mode} className="flex items-center justify-between px-4 py-2.5">
                      <span className="text-sm text-ink-300">{mode}</span>
                      <span className="num text-sm text-ink-100">{formatINR(amount)}</span>
                    </div>
                  ))
                )}
              </div>
              <div className="border-t border-carbon-700/60 px-4 py-2 text-[11px] text-ink-500">
                Customer receipts: {formatINR(dsr.data.totals.receipts)}
              </div>
            </Card>

            <Card>
              <CardHeader title="Product totals" subtitle="Aggregate litres and estimated value by grade" />
              <div className="table-wrap">
                <table className="data-table min-w-0">
                  <thead><tr><th>Product</th><th className="text-right">Litres</th><th className="text-right">Value</th></tr></thead>
                  <tbody>
                    {dsr.data.productTotals.length === 0 ? (
                      <tr><td colSpan={3} className="py-6 text-center text-xs text-ink-500">No closed shift volume.</td></tr>
                    ) : (
                      dsr.data.productTotals.map((row) => (
                        <tr key={row.code}>
                          <td className="text-ink-200">{row.product}</td>
                          <td className="num text-right">{formatNumber(row.litres, 3)}</td>
                          <td className="num text-right">{formatINR(row.value)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <Card>
            <CardHeader title="Nozzle-wise sales" subtitle="Opening and closing totalizer comparison" />
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr><th>Shift</th><th>Nozzle</th><th>Product</th><th className="text-right">Opening</th><th className="text-right">Closing</th><th className="text-right">Litres</th><th className="text-right">Rate</th><th className="text-right">Value</th><th>Status</th></tr>
                </thead>
                <tbody>
                  {dsr.data.nozzleSales.length === 0 ? (
                    <tr><td colSpan={9} className="py-8 text-center text-xs text-ink-500">No nozzle readings for this date.</td></tr>
                  ) : (
                    dsr.data.nozzleSales.map((row, index) => (
                      <tr key={String(row.id ?? index)}>
                        <td className="text-xs text-ink-400">#{String(row.shiftNumber)} <StatusBadge status={String(row.shiftStatus)} /></td>
                        <td className="font-medium text-ink-100">{String(row.nozzleLabel)}<span className="block text-[10px] text-ink-500">{String(row.dispenserCode)}{row.stationName ? ` · ${String(row.stationName)}` : ''}</span></td>
                        <td className="text-xs text-ink-300">{String(row.productName)}</td>
                        <td className="num text-right">{row.openingReading ? formatNumber(String(row.openingReading), 3) : '—'}</td>
                        <td className="num text-right">{row.closingReading ? formatNumber(String(row.closingReading), 3) : '—'}</td>
                        <td className="num text-right font-medium">{row.litresSold ? formatNumber(String(row.litresSold), 3) : '—'}</td>
                        <td className="num text-right">{row.rate ? formatINR(String(row.rate)) : '—'}</td>
                        <td className="num text-right">{row.salesValue ? formatINR(String(row.salesValue)) : '—'}</td>
                        <td><StatusBadge status={String(row.status)} /></td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </Card>

          <div className="grid gap-3 lg:grid-cols-2">
            <Card>
              <CardHeader title="Stock position" subtitle="Book stock is ledger-derived; physical dips are separate" />
              <TableScrollHint />
              <div className="table-wrap">
                <table className="data-table min-w-[560px]">
                  <thead><tr><th>Tank</th><th className="text-right">Opening</th><th className="text-right">Delivered</th><th className="text-right">Dispensed</th><th className="text-right">Book closing</th><th className="text-right">Measured</th></tr></thead>
                  <tbody>
                    {dsr.data.stock.map((tank) => (
                      <tr key={String(tank.id)}>
                        <td className="text-ink-100">{String(tank.code)}<span className="block text-[10px] text-ink-500">{String(tank.productName)}{tank.stationName ? ` · ${String(tank.stationName)}` : ''}</span></td>
                        <td className="num text-right">{formatNumber(String(tank.openingBook), 3)}</td>
                        <td className="num text-right">{formatNumber(String(tank.deliveredToday), 3)}</td>
                        <td className="num text-right">{formatNumber(String(tank.dispensedBook), 3)}</td>
                        <td className="num text-right font-medium">{formatNumber(String(tank.bookClosing), 3)}</td>
                        <td className={`num text-right ${tank.varianceFlag ? 'text-warn' : 'text-ink-400'}`}>
                          {tank.measured ? formatNumber(String(tank.measured), 3) : '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card>
              <CardHeader title="Deliveries & expenses" subtitle="Purchases received and operating costs on the date" />
              <div className="max-h-[280px] overflow-y-auto scroll-thin">
                {dsr.data.deliveries.length === 0 && dsr.data.expenses.length === 0 ? (
                  <p className="px-4 py-6 text-center text-xs text-ink-500">No deliveries or expenses recorded.</p>
                ) : (
                  <div className="divide-y divide-carbon-800/80">
                    {dsr.data.deliveries.map((d) => (
                      <div key={String(d.id)} className="flex items-start justify-between gap-3 px-4 py-2.5">
                        <div>
                          <p className="text-xs text-ink-200">Delivery · {String(d.supplierName)}</p>
                          <p className="text-[10px] text-ink-500">{String(d.invoiceNumber)} · {formatNumber(String(d.quantityLitres), 3)} L {String(d.productName)}</p>
                        </div>
                        <span className="num text-xs text-ink-300">{formatINR(String(d.totalAmount))}</span>
                      </div>
                    ))}
                    {dsr.data.expenses.map((e) => (
                      <div key={String(e.id)} className="flex items-start justify-between gap-3 px-4 py-2.5">
                        <div>
                          <p className="text-xs text-ink-200">{String(e.categoryName)}</p>
                          <p className="text-[10px] text-ink-500">{String(e.description)}</p>
                        </div>
                        <span className="num text-xs text-danger">{formatINR(String(e.amount))}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </Card>
          </div>

          <Card>
            <CardHeader title="Reconciliation status" subtitle="Latest cash reconciliation recorded for the date" />
            <div className="p-4">
              {dsr.data.reconciliation ? (
                <div className="grid gap-2 text-sm sm:grid-cols-3 lg:grid-cols-6">
                  {[
                    ['Cash expected', formatINR(String(dsr.data.reconciliation.cashExpected))],
                    ['Cash counted', formatINR(String(dsr.data.reconciliation.cashCounted))],
                    ['Digital expected', formatINR(String(dsr.data.reconciliation.digitalExpected))],
                    ['Digital settled', formatINR(String(dsr.data.reconciliation.digitalSettled))],
                    ['Variance', formatINR(String(dsr.data.reconciliation.variance))],
                    ['Status', <StatusBadge key="s" status={String(dsr.data.reconciliation.status)} />],
                  ].map(([label, value]) => (
                    <div key={String(label)}>
                      <p className="text-[10px] uppercase tracking-wider text-ink-500">{label}</p>
                      <p className="mt-0.5 text-sm text-ink-200">{value}</p>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-xs text-ink-500">No reconciliation recorded for this date.</p>
              )}
            </div>
          </Card>

          <div className="card p-4">
            <p className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-ink-400">
              <ReceiptText className="h-3.5 w-3.5" /> Report notes & disclaimer
            </p>
            <ul className="space-y-1 text-[11px] leading-relaxed text-ink-500">
              {dsr.data.notes.map((note, index) => <li key={index}>• {note}</li>)}
            </ul>
          </div>
        </div>
      ) : null}

      <StockLossCard stationId={selected} stations={stations} />
      <AgeingCard stationId={stationId} />
      <ExportsCard stationId={selected} />
    </>
  );
}

interface StockLossRow {
  stationName?: string;
  productName: string;
  productCode: string;
  openingLitres: string;
  receiptsLitres: string;
  dispensedLitres: string;
  adjustmentsLitres: string;
  closingBookLitres: string;
  physicalLitres: string | null;
  /** physical − book: positive = gain (zyada mila), negative = loss (kam mila). */
  lossGainLitres: string | null;
  lossGainPctOfSales: string | null;
  normPct: string;
  status: 'WITHIN_NORM' | 'BEYOND_NORM' | 'GAIN_CHECK' | 'NO_DIP';
}

function nozzleRows(report: Dsr) {
  return report.nozzleSales.map((row) => ({
    pump: row.stationName ?? report.station.name,
    shift: row.shiftNumber,
    nozzle: row.nozzleLabel,
    dispenser: row.dispenserCode,
    product: row.productName,
    opening: row.openingReading,
    closing: row.closingReading,
    litres: row.litresSold,
    rate: row.rate,
    value: row.salesValue,
    status: row.status,
  }));
}

function StockLossCard({ stationId, stations }: { stationId: string; stations: { id: string; name: string }[] }) {
  const { toast } = useToast();
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const report = useQuery<{ month: string; rows: StockLossRow[] }>({
    queryKey: ['stock-loss', stationId, month],
    queryFn: async () => stationId === 'all' ? { month, rows: (await Promise.all(stations.map(async (s) => { const report = await apiGet<{ rows: StockLossRow[] }>(`/reports/stock-loss?stationId=${s.id}&month=${month}`); return report.rows.map((r) => ({ ...r, stationName: s.name })); }))).flat() } : apiGet(`/reports/stock-loss?stationId=${stationId}&month=${month}`),
    enabled: Boolean(stationId),
  });
  const rows = report.data?.rows ?? [];
  const tone = (status: StockLossRow['status']) => (status === 'BEYOND_NORM' ? 'danger' : status === 'GAIN_CHECK' ? 'warning' : status === 'NO_DIP' ? 'neutral' : 'success');
  const label = (status: StockLossRow['status']) => ({ WITHIN_NORM: 'Within OMC norm', BEYOND_NORM: 'Exceeds loss norm', GAIN_CHECK: 'Excess gain — verify', NO_DIP: 'No closing dip' })[status];

  return (
    <Card className="mt-4">
      <CardHeader
        title="Monthly stock loss / gain analysis"
        subtitle="Book stock vs physical dip — OMC evaporation norms: MS 0.75% · HSD 0.25% of dispensed volume."
        action={(
          <>
            <Field label="Month" className="mb-0 w-40">
              <Input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
            </Field>
            <Button size="sm" variant="ghost" icon={<Share2 className="h-3.5 w-3.5" />} disabled={rows.length === 0} onClick={() => void shareReport({
              title: `Stock loss ${month}`,
              filename: `stock-loss-${month}.csv`,
              text: rows.map((row) => `${row.stationName ? `${row.stationName} · ` : ''}${row.productName}: ${row.lossGainLitres ?? 'no dip'} L (${row.status})`).join('\n'),
              rows: rows.map((row) => ({ pump: row.stationName ?? '', product: row.productName, opening: row.openingLitres, receipts: row.receiptsLitres, dispensed: row.dispensedLitres, bookClosing: row.closingBookLitres, physical: row.physicalLitres, lossGain: row.lossGainLitres, pct: row.lossGainPctOfSales, status: row.status })),
            }).then((mode) => toast(mode === 'whatsapp' ? 'Opened WhatsApp' : 'Stock statement shared'))}
            >Share</Button>
            <Button size="sm" variant="secondary" icon={<Download className="h-3.5 w-3.5" />} disabled={rows.length === 0} onClick={() => downloadCsv(`stock-loss-${month}.csv`, rows.map((row) => ({ pump: row.stationName ?? '', product: row.productName, opening: row.openingLitres, receipts: row.receiptsLitres, dispensed: row.dispensedLitres, bookClosing: row.closingBookLitres, physical: row.physicalLitres, lossGain: row.lossGainLitres, pct: row.lossGainPctOfSales, norm: row.normPct, status: row.status })))}>Download</Button>
          </>
        )}
      />
      <TableScrollHint />
      {report.isLoading ? (
        <LoadingBlock rows={3} />
      ) : report.isError ? (
        <div className="p-4"><ErrorState error={report.error} onRetry={() => void report.refetch()} /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No product stock movements" description="No tank movements recorded for the selected month." />
      ) : (
        <div className="table-wrap">
          <table className="data-table min-w-[880px]">
            <thead>
              <tr>
                <th>Product</th>
                <th className="text-right">Opening (L)</th>
                <th className="text-right">Receipts (L)</th>
                <th className="text-right">Dispensed (L)</th>
                <th className="text-right">Book closing (L)</th>
                <th className="text-right">Physical dip (L)</th>
                <th className="text-right">Loss / Gain (L)</th>
                <th className="text-right">% of sale</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.stationName ?? stationId}-${row.productCode}`}>
                  <td className="font-semibold text-ink-100">{row.productName}{row.stationName && <span className="block text-xs font-normal text-ink-500">{row.stationName}</span>}</td>
                  <td className="num text-right">{formatNumber(row.openingLitres, 0)}</td>
                  <td className="num text-right">{formatNumber(row.receiptsLitres, 0)}</td>
                  <td className="num text-right">{formatNumber(row.dispensedLitres, 0)}</td>
                  <td className="num text-right">{formatNumber(row.closingBookLitres, 0)}</td>
                  <td className="num text-right">{row.physicalLitres ? formatNumber(row.physicalLitres, 0) : '—'}</td>
                  <td className={`num text-right ${row.lossGainLitres && Number(row.lossGainLitres) < 0 ? 'font-semibold text-danger' : row.lossGainLitres && Number(row.lossGainLitres) > 0 ? 'text-warn' : 'text-ink-300'}`}>
                    {row.lossGainLitres
                      ? `${Number(row.lossGainLitres) >= 0 ? '+' : ''}${formatNumber(row.lossGainLitres, 0)}${Number(row.lossGainLitres) >= 0 ? ' gain' : ' loss'}`
                      : '—'}
                  </td>
                  <td className="num text-right">{row.lossGainPctOfSales ? `${formatNumber(row.lossGainPctOfSales, 2)}%` : '—'}<span className="block text-[10px] text-ink-500">norm ±{row.normPct}%</span></td>
                  <td><Badge tone={tone(row.status)} dot>{label(row.status)}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="border-t border-carbon-700/70 px-4 py-2.5 text-[11px] text-ink-500">
        When loss exceeds OMC evaporation norms, inspect nozzle calibration (5L measure), TT decantation dip records, and underground line integrity.
      </p>
    </Card>
  );
}

interface AgeingRow {
  customerId: string;
  code: string;
  name: string;
  creditLimit: string;
  outstanding: string;
  bucket0To30: string;
  bucket31To60: string;
  bucket61To90: string;
  bucket90Plus: string;
  oldestUnpaidAt: string | null;
  overLimit: boolean;
}

type AgeingTotals = { outstanding: string; bucket0To30: string; bucket31To60: string; bucket61To90: string; bucket90Plus: string };

function AgeingCard({ stationId }: { stationId: string }) {
  const { toast } = useToast();
  const report = useQuery<{ asOf: string; rows: AgeingRow[]; totals: AgeingTotals }>({
    queryKey: ['ageing', stationId],
    queryFn: () => apiGet(`/reports/ageing?stationId=${stationId}`),
    enabled: Boolean(stationId),
  });
  const rows = report.data?.rows ?? [];
  const bucketCell = (value: string, hot = false) => (
    <span className={Number(value) > 0 ? (hot ? 'font-semibold text-danger' : 'text-ink-200') : 'text-ink-600'}>
      {Number(value) > 0 ? formatINR(value) : '—'}
    </span>
  );

  return (
    <Card className="mt-4">
      <CardHeader
        title="Customer receivables ageing (FIFO)"
        subtitle="Outstanding fleet credit bucketed by invoice age (oldest unpaid slips settled first)."
        action={rows.length === 0 ? undefined : (
          <>
            <Button size="sm" variant="ghost" icon={<Share2 className="h-3.5 w-3.5" />} onClick={() => void shareReport({
              title: 'Credit ageing',
              filename: 'credit-ageing.csv',
              text: `Outstanding ₹${report.data?.totals.outstanding ?? '0'} · 90+ days ₹${report.data?.totals.bucket90Plus ?? '0'}`,
              rows: rows.map((row) => ({ customer: row.name, code: row.code, outstanding: row.outstanding, d0_30: row.bucket0To30, d31_60: row.bucket31To60, d61_90: row.bucket61To90, d90: row.bucket90Plus, overLimit: row.overLimit })),
            }).then((mode) => toast(mode === 'whatsapp' ? 'Opened WhatsApp' : 'Ageing shared'))}>Share</Button>
            <Button size="sm" variant="secondary" icon={<Download className="h-3.5 w-3.5" />} onClick={() => downloadCsv('credit-ageing.csv', rows.map((row) => ({ customer: row.name, code: row.code, limit: row.creditLimit, outstanding: row.outstanding, d0_30: row.bucket0To30, d31_60: row.bucket31To60, d61_90: row.bucket61To90, d90: row.bucket90Plus })))}>Download</Button>
          </>
        )}
      />
      <TableScrollHint />
      {report.isLoading ? (
        <LoadingBlock rows={3} />
      ) : report.isError ? (
        <div className="p-4"><ErrorState error={report.error} onRetry={() => void report.refetch()} /></div>
      ) : rows.length === 0 ? (
        <EmptyState title="No outstanding receivables" description="All customer credit balances are currently settled." />
      ) : (
        <div className="table-wrap">
          <table className="data-table min-w-[780px]">
            <thead>
              <tr>
                <th>Customer</th>
                <th className="text-right">Total outstanding</th>
                <th className="text-right">0–30 days</th>
                <th className="text-right">31–60 days</th>
                <th className="text-right">61–90 days</th>
                <th className="text-right">90+ days</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.customerId}>
                  <td>
                    <span className="font-semibold text-ink-100">{row.name}</span>
                    {row.overLimit && <Badge tone="danger" className="ml-2">Over limit</Badge>}
                    <span className="block text-[10px] text-ink-500">{row.code} · limit {formatINR(row.creditLimit)}{row.oldestUnpaidAt ? ` · Oldest unpaid ${formatDate(row.oldestUnpaidAt)}` : ''}</span>
                  </td>
                  <td className="num text-right font-semibold text-ink-100">{formatINR(row.outstanding)}</td>
                  <td className="num text-right">{bucketCell(row.bucket0To30)}</td>
                  <td className="num text-right">{bucketCell(row.bucket31To60)}</td>
                  <td className="num text-right">{bucketCell(row.bucket61To90, true)}</td>
                  <td className="num text-right">{bucketCell(row.bucket90Plus, true)}</td>
                </tr>
              ))}
              {report.data && (
                <tr className="border-t-2 border-carbon-600 bg-carbon-900/50">
                  <td className="font-bold text-ink-100">Total</td>
                  <td className="num text-right font-bold text-ink-100">{formatINR(report.data.totals.outstanding)}</td>
                  <td className="num text-right font-semibold">{formatINR(report.data.totals.bucket0To30)}</td>
                  <td className="num text-right font-semibold">{formatINR(report.data.totals.bucket31To60)}</td>
                  <td className="num text-right font-semibold">{formatINR(report.data.totals.bucket61To90)}</td>
                  <td className="num text-right font-bold text-danger">{formatINR(report.data.totals.bucket90Plus)}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function ExportsCard({ stationId }: { stationId: string }) {
  const { toast } = useToast();
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const [busy, setBusy] = useState('');
  const filenameOf = (name: string) => `${name}-${month}.csv`;
  const pathOf = (slug: string) => {
    const effectiveStation = stationId === 'all' && (slug === 'ageing' || slug === 'lubricants-gst') ? '' : stationId;
    return `/reports/export/${slug}.csv?stationId=${effectiveStation}&month=${month}`;
  };

  const download = async (slug: string, filename: string) => {
    setBusy(slug);
    try {
      await apiDownload(pathOf(slug), filename);
    } catch (error) {
      toast('Export failed', { tone: 'danger', description: (error as Error).message });
    } finally {
      setBusy('');
    }
  };

  const share = async (slug: string, name: string) => {
    setBusy(`share-${slug}`);
    try {
      const blob = await apiBlob(pathOf(slug));
      const mode = await shareReport({
        title: `${name} · ${month}`,
        filename: filenameOf(slug),
        text: `${name} for ${month}. Open the attached CSV in Excel or Tally, or download it from PetroFlow.`,
        blob,
      });
      toast(mode === 'whatsapp' ? 'Opened WhatsApp' : 'Report shared', { description: 'The CSV is ready to send.' });
    } catch (error) {
      toast('Export failed', { tone: 'danger', description: (error as Error).message });
    } finally {
      setBusy('');
    }
  };

  const items = [
    { slug: 'sales-register', name: 'Sales Register', detail: 'Monthly product & payment-mode sales ledger (CA / Tally ready)' },
    { slug: 'stock-statement', name: 'Stock Statement', detail: 'Tank-wise opening, receipts, dispensed & closing stock (OMC format)' },
    { slug: 'ageing', name: 'Credit Ageing Schedule', detail: 'Customer-wise FIFO ageing buckets & credit limits' },
    ...(stationId === 'all' ? [] : [{ slug: 'lubricants-gst', name: 'Lubricants GST Register', detail: 'Taxable value & GST split for packaged oils (GSTR-ready)' }]),
  ];

  return (
    <Card className="mt-4">
      <CardHeader
        title="Accounting & statutory CSV exports"
        subtitle="Download or share a month's CSV with your CA, Tally, Excel or WhatsApp."
        action={(
          <Field label="Month" className="mb-0 w-44">
            <Input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
          </Field>
        )}
      />
      <div className="grid gap-3 p-5 sm:grid-cols-2">
        {items.map((item) => (
          <div key={item.slug} className="flex items-start gap-3 rounded-xl border border-carbon-700/80 bg-carbon-900/60 p-3.5">
            <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-accent/25 bg-accent/10 text-accent"><Download className="h-4 w-4" /></span>
            <div className="min-w-0 flex-1">
              <p className="text-sm font-bold text-ink-100">{item.name}</p>
              <p className="mt-0.5 text-xs text-ink-500">{item.detail}</p>
              <div className="mt-2 flex flex-wrap gap-2">
                <Button size="sm" variant="secondary" aria-label={`Download ${item.name}`} disabled={busy === item.slug} onClick={() => void download(item.slug, filenameOf(item.slug))}>Download</Button>
                <Button size="sm" variant="ghost" icon={<Share2 className="h-3.5 w-3.5" />} disabled={busy === `share-${item.slug}`} onClick={() => void share(item.slug, item.name)}>Share</Button>
              </div>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}
