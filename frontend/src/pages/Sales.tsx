import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Fuel, Gauge, ReceiptText } from 'lucide-react';
import { apiGet } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useAuth, useStations } from '../lib/auth';
import { formatDateTime, formatINR, formatLitres, formatNumber, todayIst } from '../lib/format';
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Field,
  Input,
  LoadingBlock,
  PageHeader,
  Select,
  StatCard,
  StatusBadge,
  Tabs,
} from '../components/ui';

interface DsrNozzleSale {
  id: string;
  nozzleLabel: string;
  nozzleNumber: number;
  dispenserCode: string;
  productName: string;
  productColor: string;
  shiftNumber: number;
  shiftStatus: string;
  openingReading: string | null;
  closingReading: string | null;
  litresSold: string | null;
  rate: string | null;
  salesValue: string | null;
  varianceLitres: string | null;
  status: string;
}

interface Dsr {
  date: string;
  station: { name: string };
  nozzleSales: DsrNozzleSale[];
  productTotals: { product: string; code: string; litres: string; value: string }[];
  paymentMix: Record<string, string>;
  totals: {
    aggregateLitres: string;
    aggregateEstimatedValue: string;
    transactionLitres: string;
    transactionValue: string;
  };
  shifts: { id: string; shiftNumber: number; status: string }[];
  notes: string[];
}

interface MeterReading {
  id: string;
  readingValue: string;
  unit: string;
  deltaLitres: string | null;
  quality: string;
  source: string;
  readingAt: string;
  sequence: number;
  note: string | null;
}

interface Nozzle {
  id: string;
  label: string;
  number: number;
  dispenserId: string;
}

export default function Sales() {
  const { can } = useAuth();
  const { stationId } = useStations();
  const [date, setDate] = useState(todayIst());
  const [tab, setTab] = useState('nozzles');
  const [nozzleId, setNozzleId] = useState('');

  const dsr = useQuery({
    queryKey: ['dsr', stationId, date],
    queryFn: () => apiGet<Dsr>(`/reports/dsr?stationId=${stationId}&date=${date}`),
    enabled: Boolean(stationId) && can('reports.read'),
  });

  const nozzles = useQuery({
    queryKey: ['nozzles', stationId],
    queryFn: () => apiGet<Nozzle[]>(`/catalog/nozzles?stationId=${stationId}&pageSize=100`),
    enabled: Boolean(stationId),
  });

  const selectedNozzle = (nozzles.data ?? []).find((n) => n.id === nozzleId) ?? nozzles.data?.[0];
  const activeNozzleId = selectedNozzle?.id ?? '';
  const history = useQuery({
    queryKey: ['nozzle-history', activeNozzleId],
    queryFn: () => apiGet<MeterReading[]>(`/catalog/nozzles/${activeNozzleId}/history`),
    enabled: Boolean(activeNozzleId) && can('readings.read') && (tab === 'meter' || !can('reports.read')),
  });

  if (!stationId) return <EmptyState title="No station assigned" />;

  const data = dsr.data;
  const meterHistory = (
  <Card className="mt-3">
    <CardHeader
      title="Meter reading history"
      subtitle="Raw totalizer snapshots with source and quality — the audit base for shift calculations"
      action={
        <Select
          value={activeNozzleId}
          onChange={(e) => setNozzleId(e.target.value)}
          className="h-9 w-full max-w-[11rem] py-1 text-xs sm:h-8 sm:w-44"
        >
          {(nozzles.data ?? []).map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
        </Select>
      }
    />
    {!can('readings.read') ? (
    <EmptyState title="Meter history is not available for your role" />
  ) : nozzles.isLoading || history.isLoading ? (
      <LoadingBlock rows={5} />
    ) : history.isError ? (
      <ErrorState error={history.error} onRetry={() => void history.refetch()} />
    ) : (history.data ?? []).length === 0 ? (
      <EmptyState title="No readings for this nozzle" description="Use the simulator or manual entry to capture a totalizer." />
    ) : (
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Read at</th>
              <th className="text-right">Totalizer</th>
              <th className="text-right">Delta</th>
              <th>Quality</th>
              <th>Source</th>
              <th>Sequence</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {(history.data ?? []).map((reading) => (
              <tr key={reading.id}>
                <td className="text-xs text-ink-300">{formatDateTime(reading.readingAt)}</td>
                <td className="num text-right">{formatNumber(reading.readingValue, 3)} {reading.unit}</td>
                <td className="num text-right">{reading.deltaLitres ? formatLitres(reading.deltaLitres) : '—'}</td>
                <td><StatusBadge status={reading.quality} /></td>
                <td><Badge tone={reading.source === 'SIMULATOR' ? 'warning' : reading.source === 'MANUAL' ? 'info' : 'neutral'}>{reading.source}</Badge></td>
                <td className="num text-xs text-ink-500">{reading.sequence}</td>
                <td className="max-w-[260px] text-[11px] text-ink-500">{reading.note ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
  </Card>
  );

  // The operator role can read meters but not the financial DSR. Give it the
  // permitted history screen rather than requesting a forbidden report.
  if (!can('reports.read')) {
    return (
      <>
        <PageHeader title="Nozzle sales & meter history" description="Meter readings for your assigned station. Financial reports require additional access." />
        {meterHistory}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Nozzle sales & meter history"
        description="Shift-derived nozzle sales and the immutable totalizer reading trail"
        actions={
          <>
            <ExportCsvButton path={`/readings?stationId=${stationId}`} filename="petroflow-meter-readings" label="Export readings" />
            <Field label="Business date" className="mb-0">
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} className="w-auto" />
            </Field>
          </>
        }
      />

      {dsr.isLoading ? (
        <LoadingBlock rows={4} />
      ) : dsr.isError ? (
        <ErrorState error={dsr.error} onRetry={() => void dsr.refetch()} />
      ) : data ? (
        <>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Shift aggregate litres" value={formatLitres(data.totals.aggregateLitres)} hint="From closed shift totalizers" icon={<Fuel className="h-4 w-4" />} tone="accent" />
            <StatCard label="Aggregate estimated value" value={formatINR(data.totals.aggregateEstimatedValue, true)} hint="Totalizer-derived estimate" icon={<ReceiptText className="h-4 w-4" />} />
            <StatCard label="Recorded transactions" value={formatINR(data.totals.transactionValue, true)} hint={`${formatLitres(data.totals.transactionLitres)} transaction-level`} icon={<Gauge className="h-4 w-4" />} />
            <StatCard
              label="Payment mix (recorded)"
              value={Object.keys(data.paymentMix).length ? formatINR(Object.values(data.paymentMix).reduce((a, b) => a + Number(b), 0), true) : '—'}
              hint={Object.entries(data.paymentMix).map(([k, v]) => `${k} ${formatINR(v, true)}`).join(' · ') || 'No transaction-level sales'}
            />
          </div>

          <div className="mt-3">
            <Tabs
              tabs={[
                { key: 'nozzles', label: 'Nozzle sales', count: data.nozzleSales.length },
                { key: 'meter', label: 'Meter history' },
                { key: 'products', label: 'Product totals', count: data.productTotals.length },
              ]}
              active={tab}
              onChange={setTab}
            />
          </div>

          {tab === 'nozzles' && (
            <Card className="mt-3">
              <CardHeader
                title="Nozzle sales for the business date"
                subtitle="Litres = closing totalizer − opening totalizer (+ approved adjustments)"
              />
              {data.nozzleSales.length === 0 ? (
                <EmptyState title="No nozzle sales recorded" description="Close a shift for this date to derive nozzle volumes." icon={<Fuel className="h-4 w-4" />} />
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Nozzle</th>
                        <th>Shift</th>
                        <th className="text-right">Opening</th>
                        <th className="text-right">Closing</th>
                        <th className="text-right">Litres</th>
                        <th className="text-right">Rate</th>
                        <th className="text-right">Value</th>
                        <th className="text-right">Variance</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.nozzleSales.map((row) => (
                        <tr key={row.id}>
                          <td>
                            <p className="font-medium text-ink-100">{row.nozzleLabel}</p>
                            <p className="text-[10px] text-ink-500">{row.dispenserCode} · {row.productName}</p>
                          </td>
                          <td className="text-xs text-ink-400">#{row.shiftNumber} <StatusBadge status={row.shiftStatus} /></td>
                          <td className="num text-right">{row.openingReading ? formatNumber(row.openingReading, 3) : '—'}</td>
                          <td className="num text-right">{row.closingReading ? formatNumber(row.closingReading, 3) : '—'}</td>
                          <td className="num text-right font-medium text-ink-100">{row.litresSold ? formatNumber(row.litresSold, 3) : '—'}</td>
                          <td className="num text-right text-ink-400">{row.rate ? formatINR(row.rate) : '—'}</td>
                          <td className="num text-right">{row.salesValue ? formatINR(row.salesValue) : '—'}</td>
                          <td className={`num text-right ${row.varianceLitres && Math.abs(Number(row.varianceLitres)) > 1 ? 'text-warn' : 'text-ink-500'}`}>
                            {row.varianceLitres ? `${formatNumber(row.varianceLitres, 3)} L` : '—'}
                          </td>
                          <td><StatusBadge status={row.status} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <p className="border-t border-carbon-700/60 px-4 py-3 text-[11px] text-ink-500">
                Totalizer-only equipment yields aggregate estimated sales per shift. Individual vehicle transactions are
                not derived from cumulative readings.
              </p>
            </Card>
          )}

          {tab === 'meter' && meterHistory}

          {tab === 'products' && (
            <Card className="mt-3">
              <CardHeader title="Product totals" subtitle="Aggregate litres and estimated value by fuel grade" />
              {data.productTotals.length === 0 ? (
                <EmptyState title="No product volume" description="Close a shift with valid readings to populate product totals." />
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr><th>Product</th><th>Code</th><th className="text-right">Litres</th><th className="text-right">Estimated value</th></tr>
                    </thead>
                    <tbody>
                      {data.productTotals.map((row) => (
                        <tr key={row.code}>
                          <td className="font-medium text-ink-100">{row.product}</td>
                          <td><Badge tone="muted">{row.code}</Badge></td>
                          <td className="num text-right">{formatNumber(row.litres, 3)} L</td>
                          <td className="num text-right">{formatINR(row.value)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <ul className="space-y-1 border-t border-carbon-700/60 px-4 py-3 text-[11px] text-ink-500">
                {data.notes.map((note, i) => <li key={i}>• {note}</li>)}
              </ul>
            </Card>
          )}
        </>
      ) : null}
    </>
  );
}
