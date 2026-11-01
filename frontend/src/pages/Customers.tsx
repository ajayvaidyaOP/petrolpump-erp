import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BookOpen, IndianRupee, Phone, Plus, Printer, Trash2, Truck, UserPlus, Users } from 'lucide-react';
import { apiDelete, apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { useIdempotencyKey } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDate, formatDateTime, formatINR, formatNumber, todayIst } from '../lib/format';
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
  Modal,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatCard,
  StatusBadge,
  Textarea,
  useToast,
} from '../components/ui';

interface Customer {
  id: string;
  code: string;
  name: string;
  phone: string | null;
  email: string | null;
  gstin: string | null;
  address: string | null;
  creditLimit: string;
  creditDays: number;
  balance: string;
  status: string;
}

interface LedgerEntry {
  id: string;
  type: string;
  amount: string;
  reference: string | null;
  slipNumber: string | null;
  vehicleNumber: string | null;
  productName: string | null;
  quantityLitres: string | null;
  ratePerLitre: string | null;
  occurredAt: string;
  dueDate: string | null;
  note: string | null;
  runningBalance: string;
  stationName: string | null;
}

interface Vehicle {
  id: string;
  vehicleNumber: string;
  vehicleType: string | null;
  driverName: string | null;
  isActive: number;
}

interface Ledger {
  customer: Customer;
  ledger: LedgerEntry[];
  closingBalance: string;
  creditLimit: string;
  availableCredit: string;
  overdueAmount: string;
}

interface Product { id: string; code: string; name: string; isActive: number }

const emptyCustomer = {
  code: '',
  name: '',
  phone: '',
  email: '',
  gstin: '',
  address: '',
  creditLimit: '50000',
  creditDays: 15,
  status: 'ACTIVE',
};

export default function Customers() {
  const { can } = useAuth();
  const { stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({ ...emptyCustomer });
  const [editCustomer, setEditCustomer] = useState<Customer | null>(null);
  const [ledgerCustomer, setLedgerCustomer] = useState<Customer | null>(null);
  const [saleOpen, setSaleOpen] = useState(false);
  const [receiptOpen, setReceiptOpen] = useState(false);
  const [statementOpen, setStatementOpen] = useState(false);

  const list = useQuery({
    queryKey: ['customers', search, page],
    queryFn: () => apiList<Customer>(`/catalog/customers?page=${page}&pageSize=15${search ? `&search=${encodeURIComponent(search)}` : ''}`),
  });

  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => apiGet<Product[]>('/catalog/products?pageSize=50'),
    enabled: can('products.read'),
  });

  const ledger = useQuery({
    queryKey: ['customer-ledger', ledgerCustomer?.id],
    queryFn: () => apiGet<Ledger>(`/finance/customer-ledger?customerId=${ledgerCustomer!.id}`),
    enabled: Boolean(ledgerCustomer) && can('reports.read'),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['customers'] });
    void queryClient.invalidateQueries({ queryKey: ['customer-ledger'] });
    void queryClient.invalidateQueries({ queryKey: ['payments'] });
    void queryClient.invalidateQueries({ queryKey: ['finance-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const createMutation = useMutation({
    mutationFn: () => apiPost('/catalog/customers', form),
    onSuccess: () => {
      toast('Customer created');
      setCreateOpen(false);
      setForm({ ...emptyCustomer });
      invalidate();
    },
    onError: (error) => toast('Could not create customer', { tone: 'danger', description: (error as Error).message }),
  });

  const updateMutation = useMutation({
    mutationFn: () =>
      apiPatch(`/catalog/customers/${editCustomer!.id}`, {
        name: editCustomer!.name,
        phone: editCustomer!.phone,
        email: editCustomer!.email,
        gstin: editCustomer!.gstin,
        address: editCustomer!.address,
        creditLimit: editCustomer!.creditLimit,
        creditDays: editCustomer!.creditDays,
        status: editCustomer!.status,
      }),
    onSuccess: () => {
      toast('Customer updated');
      setEditCustomer(null);
      invalidate();
    },
    onError: (error) => toast('Update failed', { tone: 'danger', description: (error as Error).message }),
  });

  const rows = list.data?.data ?? [];
  const meta: ListMeta = list.data?.meta ?? { page: 1, pageSize: 15, total: 0, totalPages: 1 };
  const totalOutstanding = rows.reduce((acc, c) => acc + Number(c.balance), 0);

  return (
    <>
      <PageHeader
        title="Customers & credit ledger"
        description="Credit limits, outstanding balances, receipts and follow-up calls. Credit sales are never counted as cash received."
        actions={
          can('customers.write') ? (
            <Button size="sm" variant="primary" icon={<UserPlus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>
              New customer
            </Button>
          ) : undefined
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Customers" value={meta.total} hint="Active and blocked accounts" icon={<Users className="h-4 w-4" />} />
        <StatCard label="Outstanding (listed)" value={formatINR(totalOutstanding, true)} hint="Sum of positive customer balances" icon={<IndianRupee className="h-4 w-4" />} tone={totalOutstanding > 0 ? 'warning' : 'default'} />
        <StatCard
          label="Credit exposure"
          value={formatINR(rows.reduce((acc, c) => acc + Number(c.creditLimit), 0), true)}
          hint="Total sanctioned credit limits"
          icon={<BookOpen className="h-4 w-4" />}
        />
      </div>

      {can('customers.read') && <DueFollowups />}

      <Card className="mt-3">
        <CardHeader
          title="Customer accounts"
          subtitle="Balances are derived from the credit entry ledger"
          action={<SearchInput value={search} onChange={(v) => { setSearch(v); setPage(1); }} placeholder="Search name, code, phone…" className="w-full sm:w-56" />}
        />
        {list.isLoading ? (
          <LoadingBlock rows={5} />
        ) : list.isError ? (
          <div className="p-4"><ErrorState error={list.error} onRetry={() => void list.refetch()} /></div>
        ) : rows.length === 0 ? (
          <EmptyState title="No customers yet" description="Add credit customers to track fuel on account and receipts." action={can('customers.write') ? <Button size="sm" variant="primary" onClick={() => setCreateOpen(true)}>New customer</Button> : undefined} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Customer</th>
                    <th>Contact</th>
                    <th>Status</th>
                    <th className="text-right">Credit limit</th>
                    <th className="text-right">Balance</th>
                    <th>Limit use</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((customer) => {
                    const limit = Number(customer.creditLimit);
                    const balance = Number(customer.balance);
                    const util = limit > 0 ? Math.min(100, (balance / limit) * 100) : 0;
                    return (
                      <tr key={customer.id}>
                        <td>
                          <p className="font-medium text-ink-100">{customer.name}</p>
                          <p className="text-[10px] text-ink-500">{customer.code}{customer.gstin ? ` · ${customer.gstin}` : ''}</p>
                        </td>
                        <td className="text-xs text-ink-400">{customer.phone ?? '—'}<br /><span className="text-ink-600">{customer.email ?? ''}</span></td>
                        <td><StatusBadge status={customer.status} /></td>
                        <td className="num text-right text-ink-400">{formatINR(customer.creditLimit)}</td>
                        <td className={`num text-right ${balance > 0 ? 'text-warn' : 'text-ink-300'}`}>{formatINR(customer.balance)}</td>
                        <td className="w-36">
                          <div className="flex items-center gap-2">
                            <div className="h-2 flex-1 overflow-hidden rounded-full bg-carbon-800 ring-1 ring-inset ring-carbon-700/60">
                              <div className={`h-full rounded-full ${util > 90 ? 'bg-danger' : util > 70 ? 'bg-warn' : 'bg-accent'}`} style={{ width: `${util}%` }} />
                            </div>
                            <span className="num text-[10px] font-medium text-ink-500">{formatNumber(util, 0)}%</span>
                          </div>
                        </td>
                        <td className="text-right">
                          <div className="flex justify-end gap-1">
                            {can('reports.read') ? <Button size="sm" variant="ghost" onClick={() => setLedgerCustomer(customer)}>Ledger</Button> : can('sales.write') && <Button size="sm" variant="primary" onClick={() => { setLedgerCustomer(customer); setSaleOpen(true); }}>Credit slip</Button>}
                            {can('customers.write') && <Button size="sm" variant="ghost" onClick={() => setEditCustomer({ ...customer })}>Edit</Button>}
                          </div>
                        </td>
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

      {/* Create customer */}
      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="New credit customer"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={createMutation.isPending} disabled={!form.name || !form.code} onClick={() => createMutation.mutate()}>
              Create customer
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Customer code" required>
            <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="CUST-004" />
          </Field>
          <Field label="Name" required>
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Phone"><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
          <Field label="Email"><Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
          <Field label="GSTIN"><Input value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} /></Field>
          <Field label="Credit limit (INR)"><Input inputMode="decimal" value={form.creditLimit} onChange={(e) => setForm({ ...form, creditLimit: e.target.value })} /></Field>
          <Field label="Credit days"><Input type="number" value={form.creditDays} onChange={(e) => setForm({ ...form, creditDays: Number(e.target.value) })} /></Field>
          <Field label="Address" className="sm:col-span-2"><Textarea value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></Field>
        </div>
      </Modal>

      {/* Edit customer */}
      <Modal
        open={Boolean(editCustomer)}
        onClose={() => setEditCustomer(null)}
        title="Edit customer"
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditCustomer(null)}>Cancel</Button>
            <Button variant="primary" loading={updateMutation.isPending} onClick={() => updateMutation.mutate()}>Save changes</Button>
          </>
        }
      >
        {editCustomer && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Name"><Input value={editCustomer.name} onChange={(e) => setEditCustomer({ ...editCustomer, name: e.target.value })} /></Field>
            <Field label="Status">
              <Select value={editCustomer.status} onChange={(e) => setEditCustomer({ ...editCustomer, status: e.target.value })}>
                <option value="ACTIVE">ACTIVE</option>
                <option value="BLOCKED">BLOCKED</option>
              </Select>
            </Field>
            <Field label="Phone"><Input value={editCustomer.phone ?? ''} onChange={(e) => setEditCustomer({ ...editCustomer, phone: e.target.value })} /></Field>
            <Field label="Email"><Input value={editCustomer.email ?? ''} onChange={(e) => setEditCustomer({ ...editCustomer, email: e.target.value })} /></Field>
            <Field label="Credit limit (INR)"><Input value={editCustomer.creditLimit} onChange={(e) => setEditCustomer({ ...editCustomer, creditLimit: e.target.value })} /></Field>
            <Field label="Credit days"><Input type="number" value={editCustomer.creditDays} onChange={(e) => setEditCustomer({ ...editCustomer, creditDays: Number(e.target.value) })} /></Field>
          </div>
        )}
      </Modal>

      {/* Ledger */}
      <Modal
        open={Boolean(ledgerCustomer) && can('reports.read')}
        onClose={() => setLedgerCustomer(null)}
        title={ledgerCustomer ? `${ledgerCustomer.name} · credit ledger` : 'Credit ledger'}
        description={ledger.data ? `Closing balance ${formatINR(ledger.data.closingBalance)} · available credit ${formatINR(ledger.data.availableCredit)}` : undefined}
        size="xl"
        footer={
          <div className="flex w-full flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap gap-2">
              {can('sales.write') && (
                <Button size="sm" variant="secondary" icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setSaleOpen(true)}>
                  Credit sale
                </Button>
              )}
              {can('payments.write') && (
                <Button size="sm" variant="primary" onClick={() => setReceiptOpen(true)}>Record receipt</Button>
              )}
              <Button size="sm" variant="outline" icon={<Printer className="h-3.5 w-3.5" />} onClick={() => setStatementOpen(true)}>
                Monthly statement
              </Button>
            </div>
            <Button size="sm" variant="ghost" onClick={() => setLedgerCustomer(null)}>Close</Button>
          </div>
        }
      >
        {ledger.isLoading ? (
          <LoadingBlock rows={4} />
        ) : ledger.isError ? (
          <ErrorState error={ledger.error} onRetry={() => void ledger.refetch()} />
        ) : ledger.data ? (
          <div className="space-y-3">
            {ledgerCustomer && <VehiclesSection customerId={ledgerCustomer.id} canWrite={can('customers.write')} />}
            {ledgerCustomer && <FollowUpPanel customerId={ledgerCustomer.id} canWrite={can('customers.write')} />}
            {Number(ledger.data.overdueAmount) > 0 && (
              <div className="callout-danger">
                {formatINR(ledger.data.overdueAmount)} is past due — credit terms exceeded.
              </div>
            )}
            <TableScrollHint className="rounded-t-lg border border-b-0 border-carbon-700/60" />
            <div className="table-wrap rounded-b-lg border border-t-0 border-carbon-700/60">
              <table className="data-table min-w-[720px]">
                <thead>
                  <tr><th>Date</th><th>Pump</th><th>Type</th><th>Reference</th><th>Due</th><th className="text-right">Amount</th><th className="text-right">Running</th></tr>
                </thead>
                <tbody>
                  {ledger.data.ledger.length === 0 ? (
                    <tr><td colSpan={7} className="py-8 text-center text-xs text-ink-500">No ledger entries yet.</td></tr>
                  ) : (
                    ledger.data.ledger.map((entry) => (
                      <tr key={entry.id}>
                        <td className="text-xs text-ink-400">{formatDateTime(entry.occurredAt)}</td><td className="text-xs">{entry.stationName ?? '—'}</td>
                        <td>
                          <Badge tone={entry.type === 'CREDIT_SALE' ? 'warning' : entry.type === 'PAYMENT' ? 'success' : 'neutral'}>
                            {entry.type.replace(/_/g, ' ')}
                          </Badge>
                        </td>
                        <td className="text-xs text-ink-400">
                          <span className="font-medium text-ink-200">{entry.slipNumber ?? entry.reference ?? '—'}</span>
                          {(entry.vehicleNumber || entry.productName) && (
                            <span className="block text-[10px] text-ink-500">
                              {entry.vehicleNumber}{entry.productName ? ` · ${entry.productName}` : ''}
                              {entry.quantityLitres ? ` · ${formatNumber(entry.quantityLitres, 1)}L` : ''}
                            </span>
                          )}
                        </td>
                        <td className="text-xs text-ink-500">{entry.dueDate ? formatDate(entry.dueDate) : '—'}</td>
                        <td className={`num text-right ${entry.type === 'PAYMENT' ? 'text-ok' : 'text-warn'}`}>
                          {entry.type === 'PAYMENT' ? '−' : '+'}{formatINR(entry.amount)}
                        </td>
                        <td className="num text-right text-ink-200">{formatINR(entry.runningBalance)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <p className="text-[11px] text-ink-500">Credit sales increase the balance; receipts reduce it. A credit sale is never treated as cash received.</p>
          </div>
        ) : null}
      </Modal>

      {ledgerCustomer && (
        <>
          <CreditSaleModal
            open={saleOpen}
            onClose={() => { setSaleOpen(false); if (!can('reports.read')) setLedgerCustomer(null); }}
            customer={ledgerCustomer}
            stationId={stationId}
            products={products.data ?? []}
            onDone={() => { setSaleOpen(false); if (!can('reports.read')) setLedgerCustomer(null); invalidate(); }}
          />
          <ReceiptModal
            open={receiptOpen}
            onClose={() => setReceiptOpen(false)}
            customer={ledgerCustomer}
            stationId={stationId}
            onDone={() => { setReceiptOpen(false); invalidate(); }}
          />
          <StatementModal
            open={statementOpen}
            onClose={() => setStatementOpen(false)}
            customer={ledgerCustomer}
          />
        </>
      )}
    </>
  );
}

interface FollowUp {
  id: string;
  kind: string;
  note: string;
  promisedAmount: string | null;
  nextOn: string | null;
  createdAt: string;
  createdByName?: string | null;
  customerName?: string;
  customerCode?: string;
  customerId?: string;
  balance?: string;
}

function DueFollowups() {
  const due = useQuery<FollowUp[]>({
    queryKey: ['followups-due', todayIst()],
    queryFn: () => apiGet<FollowUp[]>(`/catalog/followups?dueBy=${todayIst()}`),
  });
  const rows = due.data ?? [];
  if (due.isLoading || rows.length === 0) return null;
  return (
    <Card className="mt-3">
      <CardHeader title="Follow-ups due" subtitle="Calls and promises dated today or earlier. Open the customer ledger to log the next one." />
      <div className="divide-y divide-carbon-800/80">
        {rows.slice(0, 6).map((row) => (
          <div key={row.id} className="flex items-start justify-between gap-3 px-4 py-2.5">
            <div>
              <p className="text-sm font-semibold text-ink-100">{row.customerName} <span className="font-normal text-ink-500">{row.customerCode}</span></p>
              <p className="text-xs text-ink-400">{row.kind} · {row.note}</p>
            </div>
            <div className="text-right">
              <Badge tone={row.nextOn && row.nextOn < todayIst() ? 'danger' : 'warning'}>{row.nextOn ? formatDate(row.nextOn) : 'Due'}</Badge>
              {row.balance && Number(row.balance) > 0 && <p className="mt-1 text-[11px] text-ink-500">Due {formatINR(row.balance)}</p>}
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

function FollowUpPanel({ customerId, canWrite }: { customerId: string; canWrite: boolean }) {
  const { toast } = useToast();
  const client = useQueryClient();
  const [note, setNote] = useState('');
  const [kind, setKind] = useState('CALL');
  const [nextOn, setNextOn] = useState('');
  const [promised, setPromised] = useState('');
  const list = useQuery<FollowUp[]>({
    queryKey: ['followups', customerId],
    queryFn: () => apiGet<FollowUp[]>(`/catalog/customers/${customerId}/followups`),
  });
  const save = useMutation({
    mutationFn: () => apiPost(`/catalog/customers/${customerId}/followups`, {
      kind, note, nextOn: nextOn || null, promisedAmount: promised || null,
    }),
    onSuccess: () => {
      toast('Follow-up saved');
      setNote('');
      setPromised('');
      void client.invalidateQueries({ queryKey: ['followups', customerId] });
      void client.invalidateQueries({ queryKey: ['followups-due'] });
    },
    onError: (error: Error) => toast('Could not save follow-up', { tone: 'danger', description: error.message }),
  });
  return (
    <div className="rounded-xl border border-carbon-700/70 p-3">
      <p className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-ink-400"><Phone className="h-3.5 w-3.5" /> Follow-ups</p>
      {canWrite && (
        <div className="mt-2 grid gap-2 sm:grid-cols-[8rem_1fr_9rem_8rem_auto]">
          <Select value={kind} onChange={(event) => setKind(event.target.value)} aria-label="Follow-up type">
            <option value="CALL">Call</option>
            <option value="VISIT">Visit</option>
            <option value="PROMISE">Promise</option>
            <option value="NOTE">Note</option>
          </Select>
          <Input value={note} onChange={(event) => setNote(event.target.value)} placeholder="What was said" />
          <Input type="date" value={nextOn} onChange={(event) => setNextOn(event.target.value)} aria-label="Next follow-up date" />
          <Input inputMode="decimal" value={promised} onChange={(event) => setPromised(event.target.value)} placeholder="Promise ₹" />
          <Button size="sm" variant="secondary" disabled={note.trim().length < 2} loading={save.isPending} onClick={() => save.mutate()}>Save</Button>
        </div>
      )}
      <div className="mt-3 space-y-2">
        {(list.data ?? []).length === 0 ? <p className="text-xs text-ink-500">No follow-ups yet. Log a call when you chase udhaar.</p> : (list.data ?? []).map((row) => (
          <div key={row.id} className="flex items-start justify-between gap-3 text-xs">
            <p className="text-ink-200"><span className="font-semibold">{row.kind}</span> · {row.note}</p>
            <p className="shrink-0 text-ink-500">{formatDate(row.createdAt)}{row.nextOn ? ` · next ${formatDate(row.nextOn)}` : ''}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

function VehiclesSection({ customerId, canWrite }: { customerId: string; canWrite: boolean }) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ vehicleNumber: '', vehicleType: 'Truck', driverName: '' });
  const vehicles = useQuery<Vehicle[]>({
    queryKey: ['customer-vehicles', customerId],
    queryFn: () => apiGet<Vehicle[]>(`/catalog/customers/${customerId}/vehicles`),
  });
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['customer-vehicles', customerId] });
  const add = useMutation({
    mutationFn: () => apiPost(`/catalog/customers/${customerId}/vehicles`, form),
    onSuccess: () => { toast('Vehicle registered'); setForm({ vehicleNumber: '', vehicleType: 'Truck', driverName: '' }); invalidate(); },
    onError: (error: Error) => toast('Could not register vehicle', { tone: 'danger', description: error.message }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => apiDelete(`/catalog/customers/${customerId}/vehicles/${id}`),
    onSuccess: invalidate,
    onError: (error: Error) => toast('Could not remove vehicle', { tone: 'danger', description: error.message }),
  });

  return (
    <div className="rounded-lg border border-carbon-700/75 bg-carbon-900/55 p-3.5">
      <p className="mb-2 flex items-center gap-1.5 text-xs font-bold text-ink-100">
        <Truck className="h-3.5 w-3.5 text-accent" /> Authorized fleet vehicles (credit slip whitelist)
      </p>
      <div className="flex flex-wrap gap-1.5">
        {(vehicles.data ?? []).map((v) => (
          <span key={v.id} className="inline-flex items-center gap-1.5 rounded-full border border-carbon-700 bg-carbon-900 py-1 pl-2.5 pr-1 text-[11px] text-ink-200">
            <span className="font-mono font-semibold tracking-wide text-ink-100">{v.vehicleNumber}</span>
            <span className="text-ink-500">{v.vehicleType ?? ''}{v.driverName ? ` · ${v.driverName}` : ''}</span>
            {canWrite && (
              <button type="button" aria-label={`Remove ${v.vehicleNumber}`} className="rounded-full p-0.5 text-ink-500 hover:text-danger" onClick={() => remove.mutate(v.id)}>
                <Trash2 className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {(vehicles.data ?? []).length === 0 && !vehicles.isLoading && (
          <span className="text-[11px] text-ink-500">No fleet vehicles registered yet — add vehicle numbers for vehicle-wise indent slips.</span>
        )}
      </div>
      {canWrite && (
        <div className="mt-2.5 grid gap-2 sm:grid-cols-[1fr_140px_1fr_auto]">
          <Input placeholder="MH12AB1234" aria-label="Vehicle number" value={form.vehicleNumber} onChange={(e) => setForm({ ...form, vehicleNumber: e.target.value.toUpperCase() })} className="py-1.5 text-xs" />
          <Select value={form.vehicleType} onChange={(e) => setForm({ ...form, vehicleType: e.target.value })} className="py-1.5 text-xs" aria-label="Vehicle type">
            {['Truck', 'Tempo', 'Tractor', 'Car', 'Bus', 'Two-wheeler', 'Other'].map((t) => <option key={t} value={t}>{t}</option>)}
          </Select>
          <Input placeholder="Driver name" aria-label="Driver name" value={form.driverName} onChange={(e) => setForm({ ...form, driverName: e.target.value })} className="py-1.5 text-xs" />
          <Button size="sm" variant="secondary" disabled={!form.vehicleNumber} loading={add.isPending} onClick={() => add.mutate()}>Add vehicle</Button>
        </div>
      )}
    </div>
  );
}

interface StatementData {
  customer: Customer;
  month: string;
  openingBalance: string;
  lines: {
    id: string; occurredAt: string; type: string; slipNumber: string | null; reference: string | null;
    vehicleNumber: string | null; driverName: string | null; stationName: string | null;
    productName: string | null; quantityLitres: string | null; ratePerLitre: string | null;
    debit: string; credit: string; runningBalance: string;
  }[];
  closingBalance: string;
  totalSales: string;
  totalPayments: string;
}

function StatementModal({ open, onClose, customer }: { open: boolean; onClose: () => void; customer: Customer }) {
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7));
  const statement = useQuery<StatementData>({
    queryKey: ['customer-statement', customer.id, month],
    queryFn: () => apiGet<StatementData>(`/finance/customer-statement?customerId=${customer.id}&month=${month}`),
    enabled: open,
  });
  const data = statement.data;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Statement — ${customer.name}`}
      description="Itemized monthly credit-slip statement ready for print or PDF export."
      size="xl"
      footer={
        <>
          <Button variant="primary" icon={<Printer className="h-4 w-4" />} onClick={() => window.print()}>Print / PDF</Button>
          <Button variant="ghost" onClick={onClose}>Close</Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Month" className="w-full sm:w-52">
          <Input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
        </Field>
        {statement.isLoading ? (
          <LoadingBlock rows={4} />
        ) : statement.isError ? (
          <ErrorState error={statement.error} onRetry={() => void statement.refetch()} />
        ) : data ? (
          <div className="rounded-xl border border-carbon-700 bg-carbon-900/60 p-4 print-area">
            <div className="flex flex-wrap justify-between gap-2 border-b border-carbon-700 pb-3">
              <div>
                <p className="font-display text-lg font-bold text-ink-100">{data.customer.name}</p>
                <p className="text-[11px] text-ink-500">
                  {data.customer.code}{data.customer.gstin ? ` · GSTIN ${data.customer.gstin}` : ''}{data.customer.phone ? ` · ${data.customer.phone}` : ''}
                </p>
              </div>
              <div className="text-right text-[11px] text-ink-400">
                <p>Statement period</p>
                <p className="num font-semibold text-ink-100">{data.month}</p>
              </div>
            </div>
            <p className="mt-3 text-xs text-ink-300">
              Opening balance: <strong className="num">{formatINR(data.openingBalance)}</strong>
            </p>
            <TableScrollHint />
            <div className="table-wrap mt-2">
              <table className="data-table min-w-[760px]">
                <thead>
                  <tr>
                    <th>Date</th><th>Pump</th><th>Slip no.</th><th>Vehicle</th><th>Product</th>
                    <th className="text-right">Litres</th><th className="text-right">Rate</th>
                    <th className="text-right">Debit (Dr)</th><th className="text-right">Credit (Cr)</th><th className="text-right">Balance</th>
                  </tr>
                </thead>
                <tbody>
                  {data.lines.length === 0 ? (
                    <tr><td colSpan={10} className="py-6 text-center text-xs text-ink-500">No credit activity in this month.</td></tr>
                  ) : data.lines.map((entry) => (
                    <tr key={entry.id}>
                      <td className="whitespace-nowrap text-xs">{formatDate(entry.occurredAt)}</td><td className="text-xs">{entry.stationName ?? '—'}</td>
                      <td className="text-xs">{entry.slipNumber ?? entry.reference ?? (entry.type === 'PAYMENT' ? 'PAYMENT' : '—')}</td>
                      <td className="text-xs">{entry.vehicleNumber ?? '—'}</td>
                      <td className="text-xs">{entry.productName ?? '—'}</td>
                      <td className="num text-right">{entry.quantityLitres ? formatNumber(entry.quantityLitres, 1) : '—'}</td>
                      <td className="num text-right">{entry.ratePerLitre ? formatINR(entry.ratePerLitre) : '—'}</td>
                      <td className="num text-right text-warn">{Number(entry.debit) > 0 ? formatINR(entry.debit) : '—'}</td>
                      <td className="num text-right text-ok">{Number(entry.credit) > 0 ? formatINR(entry.credit) : '—'}</td>
                      <td className="num text-right font-medium text-ink-100">{formatINR(entry.runningBalance)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex flex-wrap justify-end gap-5 border-t border-carbon-700 pt-3 text-xs">
              <span className="text-ink-400">Total volume: <strong className="num text-ink-100">{formatNumber(data.lines.reduce((acc, line) => acc + Number(line.quantityLitres ?? 0), 0), 1)} L</strong></span>
              <span className="text-ink-400">Monthly credit sales: <strong className="num text-warn">{formatINR(data.totalSales)}</strong></span>
              <span className="text-ink-400">Receipts: <strong className="num text-ok">{formatINR(data.totalPayments)}</strong></span>
              <span className="text-ink-200">Closing balance: <strong className="num font-display text-base text-ink-100">{formatINR(data.closingBalance)}</strong></span>
            </div>
            <p className="mt-2 text-[10px] text-ink-600">Please remit payment within the agreed credit period. Report any slip discrepancy within 7 days.</p>
          </div>
        ) : null}
      </div>
    </Modal>
  );
}

function CreditSaleModal({
  open,
  onClose,
  customer,
  stationId,
  products,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  customer: Customer;
  stationId: string;
  products: Product[];
  onDone: () => void;
}) {
  const { toast } = useToast();
  const [productId, setProductId] = useState(products[0]?.id ?? '');
  const [quantity, setQuantity] = useState('');
  const [amount, setAmount] = useState('');
  const [vehicleNumber, setVehicleNumber] = useState('');
  const [driverName, setDriverName] = useState('');
  const [slipNumber, setSlipNumber] = useState('');
  const [reference, setReference] = useState('');

  const vehicles = useQuery<Vehicle[]>({
    queryKey: ['customer-vehicles', customer.id],
    queryFn: () => apiGet<Vehicle[]>(`/catalog/customers/${customer.id}/vehicles`),
    enabled: open,
  });
  const [dueDate, setDueDate] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() + (customer.creditDays || 15));
    return d.toISOString().slice(0, 10);
  });

  const payload = {
    stationId, customerId: customer.id, productId: productId || undefined,
    quantityLitres: quantity || undefined, amount: quantity ? undefined : amount,
    slipNumber: slipNumber || undefined, vehicleNumber: vehicleNumber || undefined,
    driverName: driverName || undefined, reference: reference || undefined,
    dueDate, paymentMode: 'CREDIT',
  };
  const idempotency = useIdempotencyKey('credit-sale', payload);
  const mutation = useMutation({
    mutationFn: () => apiPost('/finance/credit-sales', payload, idempotency.key),
    onSuccess: () => {
      idempotency.reset();
      toast('Credit sale recorded', { description: 'Customer balance updated with a ledger entry.' });
      onDone();
    },
    onError: (error) => toast('Credit sale rejected', { tone: 'danger', description: (error as Error).message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Credit sale · ${customer.name}`}
      description={`Outstanding ${formatINR(customer.balance)} of limit ${formatINR(customer.creditLimit)}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={mutation.isPending} disabled={!quantity && !amount} onClick={() => mutation.mutate()}>
            Post credit sale
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Product">
          <Select value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">Select product…</option>
            {products.filter((p) => Number(p.isActive) !== 0).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </Select>
        </Field>
        <Field label="Quantity (litres)" hint="Rate is resolved from the effective price">
          <Input inputMode="decimal" value={quantity} onChange={(e) => setQuantity(e.target.value)} placeholder="e.g. 120.5" />
        </Field>
        <Field label="Or amount (INR)" hint="Use when quantity is unknown">
          <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 5000" disabled={Boolean(quantity)} />
        </Field>
        <Field label="Reference">
          <Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Invoice/slip reference" />
        </Field>
        <Field label="Due date">
          <Input type="date" value={dueDate} min={todayIst()} onChange={(e) => setDueDate(e.target.value)} />
        </Field>
        <Field label="Vehicle number" hint="Pick a registered fleet vehicle or type registration">
          <Input
            list={`vehicles-${customer.id}`}
            value={vehicleNumber}
            onChange={(e) => setVehicleNumber(e.target.value.toUpperCase())}
            placeholder="MH12AB1234"
          />
          <datalist id={`vehicles-${customer.id}`}>
            {(vehicles.data ?? []).map((v) => <option key={v.id} value={v.vehicleNumber}>{v.vehicleType ?? ''}</option>)}
          </datalist>
        </Field>
        <Field label="Driver name">
          <Input value={driverName} onChange={(e) => setDriverName(e.target.value)} placeholder="Signed on indent slip" />
        </Field>
        <Field label="Indent / P-slip number" hint="Leave blank to auto-generate slip sequence">
          <Input value={slipNumber} onChange={(e) => setSlipNumber(e.target.value)} placeholder="auto-generated" />
        </Field>
      </div>
    </Modal>
  );
}

function ReceiptModal({
  open,
  onClose,
  customer,
  stationId,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  customer: Customer;
  stationId: string;
  onDone: () => void;
}) {
  const { toast } = useToast();
  const [amount, setAmount] = useState('');
  const [mode, setMode] = useState('CASH');
  const [reference, setReference] = useState('');

  const payload = {
    stationId, direction: 'RECEIPT', partyType: 'CUSTOMER', customerId: customer.id,
    amount, mode, reference: reference || undefined,
  };
  const idempotency = useIdempotencyKey('receipt', payload);
  const mutation = useMutation({
    mutationFn: () => apiPost('/finance/payments', payload, idempotency.key),
    onSuccess: () => {
      idempotency.reset();
      toast('Receipt recorded', { description: 'Balance reduced and payment stored separately from sales.' });
      onDone();
    },
    onError: (error) => toast('Receipt failed', { tone: 'danger', description: (error as Error).message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Record receipt · ${customer.name}`}
      description={`Outstanding balance ${formatINR(customer.balance)}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={mutation.isPending} disabled={!amount} onClick={() => mutation.mutate()}>
            Record receipt
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Amount (INR)" required><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label="Mode">
          <Select value={mode} onChange={(e) => setMode(e.target.value)}>
            {['CASH', 'UPI', 'CARD', 'BANK'].map((m) => <option key={m} value={m}>{m}</option>)}
          </Select>
        </Field>
        <Field label="Reference" className="sm:col-span-2">
          <Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="UPI/cheque/transaction reference" />
        </Field>
      </div>
      <p className="mt-3 text-[11px] text-ink-500">
        Recording a receipt does not imply bank settlement. Digital payments can be marked settled later with a
        settlement reference.
      </p>
    </Modal>
  );
}
