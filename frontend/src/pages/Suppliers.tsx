import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Banknote, BookOpen, Plus, Truck } from 'lucide-react';
import { apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { useIdempotencyKey } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDate, formatDateTime, formatINR, todayIst } from '../lib/format';
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

interface Supplier {
  id: string;
  code: string;
  name: string;
  gstin: string | null;
  contactPerson: string | null;
  phone: string | null;
  email: string | null;
  address: string | null;
  creditDays: number;
  openingBalance: string;
  isActive: number;
}

interface LedgerEntry {
  id: string;
  date: string;
  type: string;
  description: string;
  reference: string | null;
  debit: string;
  credit: string;
  runningBalance: string;
}

interface Ledger {
  supplier: Supplier;
  ledger: LedgerEntry[];
  closingBalance: string;
  openingBalance: string;
}

const empty = {
  code: '',
  name: '',
  gstin: '',
  contactPerson: '',
  phone: '',
  email: '',
  address: '',
  creditDays: 15,
  openingBalance: '0',
};

export default function Suppliers() {
  const { can, me } = useAuth();
  const { stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const isOwner = me?.user?.roleKey === 'OWNER';

  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({ ...empty });
  const [editSupplier, setEditSupplier] = useState<Supplier | null>(null);
  const [ledgerSupplier, setLedgerSupplier] = useState<Supplier | null>(null);
  const [paymentOpen, setPaymentOpen] = useState(false);

  const list = useQuery({
    queryKey: ['suppliers', search, page],
    queryFn: () => apiList<Supplier>(`/catalog/suppliers?page=${page}&pageSize=15${search ? `&search=${encodeURIComponent(search)}` : ''}`),
  });

  const ledger = useQuery({
    queryKey: ['supplier-ledger', ledgerSupplier?.id, stationId],
    queryFn: () => apiGet<Ledger>(`/finance/supplier-ledger?supplierId=${ledgerSupplier!.id}&stationId=${stationId}`),
    enabled: Boolean(ledgerSupplier),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['suppliers'] });
    void queryClient.invalidateQueries({ queryKey: ['supplier-ledger'] });
    void queryClient.invalidateQueries({ queryKey: ['payments'] });
    void queryClient.invalidateQueries({ queryKey: ['finance-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const createMutation = useMutation({
    mutationFn: () => apiPost('/catalog/suppliers', isOwner ? form : { ...form, openingBalance: '0' }),
    onSuccess: () => {
      toast('Supplier created');
      setCreateOpen(false);
      setForm({ ...empty });
      invalidate();
    },
    onError: (error) => toast('Could not create supplier', { tone: 'danger', description: (error as Error).message }),
  });

  const updateMutation = useMutation({
    mutationFn: () =>
      apiPatch(`/catalog/suppliers/${editSupplier!.id}`, {
        name: editSupplier!.name,
        gstin: editSupplier!.gstin,
        contactPerson: editSupplier!.contactPerson,
        phone: editSupplier!.phone,
        email: editSupplier!.email,
        address: editSupplier!.address,
        creditDays: editSupplier!.creditDays,
        isActive: Boolean(editSupplier!.isActive),
      }),
    onSuccess: () => {
      toast('Supplier updated');
      setEditSupplier(null);
      invalidate();
    },
    onError: (error) => toast('Update failed', { tone: 'danger', description: (error as Error).message }),
  });

  const rows = list.data?.data ?? [];
  const meta: ListMeta = list.data?.meta ?? { page: 1, pageSize: 15, total: 0, totalPages: 1 };

  return (
    <>
      <PageHeader
        title="Suppliers & payables"
        description="Fuel suppliers, purchase invoices and payout history. Payables are computed from received deliveries less payouts."
        actions={
          can('suppliers.write') ? (
            <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setCreateOpen(true)}>New supplier</Button>
          ) : undefined
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Suppliers" value={meta.total} hint="Active fuel and service suppliers" icon={<Truck className="h-4 w-4" />} />
        <StatCard
          label="Opening balances"
          value={formatINR(meta.openingBalanceTotal ?? rows.reduce((a, s) => a + Number(s.openingBalance), 0), true)}
          hint={isOwner ? 'Carried forward payables' : 'Station-scoped (tenant opening restricted)'}
          icon={<BookOpen className="h-4 w-4" />}
        />
        <StatCard label="Payment control" value="Ledger-based" hint="Payouts are recorded separately from purchases" icon={<Banknote className="h-4 w-4" />} />
      </div>

      <Card className="mt-3">
        <CardHeader
          title="Supplier directory"
          action={<SearchInput value={search} onChange={(v) => { setSearch(v); setPage(1); }} placeholder="Search suppliers…" className="w-full sm:w-56" />}
        />
        {list.isLoading ? (
          <LoadingBlock rows={4} />
        ) : list.isError ? (
          <div className="p-4"><ErrorState error={list.error} onRetry={() => void list.refetch()} /></div>
        ) : rows.length === 0 ? (
          <EmptyState title="No suppliers" description="Add the fuel suppliers you receive deliveries from." action={can('suppliers.write') ? <Button size="sm" variant="primary" onClick={() => setCreateOpen(true)}>New supplier</Button> : undefined} />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr><th>Supplier</th><th>Contact</th><th>GSTIN</th><th className="text-right">Credit days</th><th>Status</th><th /></tr>
                </thead>
                <tbody>
                  {rows.map((supplier) => (
                    <tr key={supplier.id}>
                      <td>
                        <p className="font-medium text-ink-100">{supplier.name}</p>
                        <p className="text-[10px] text-ink-500">{supplier.code}</p>
                      </td>
                      <td className="text-xs text-ink-400">{supplier.contactPerson ?? '—'}<br /><span className="text-ink-600">{supplier.phone ?? supplier.email ?? ''}</span></td>
                      <td className="font-mono text-xs text-ink-400">{supplier.gstin ?? '—'}</td>
                      <td className="num text-right">{supplier.creditDays}</td>
                      <td><StatusBadge status={supplier.isActive ? 'ACTIVE' : 'INACTIVE'} /></td>
                      <td className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button size="sm" variant="ghost" onClick={() => setLedgerSupplier(supplier)}>Ledger</Button>
                          {can('suppliers.write') && <Button size="sm" variant="ghost" onClick={() => setEditSupplier({ ...supplier })}>Edit</Button>}
                        </div>
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
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="New supplier"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={createMutation.isPending} disabled={!form.name || !form.code} onClick={() => createMutation.mutate()}>Create supplier</Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Code" required><Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="IOCL" /></Field>
          <Field label="Name" required><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label="GSTIN"><Input value={form.gstin} onChange={(e) => setForm({ ...form, gstin: e.target.value })} /></Field>
          <Field label="Contact person"><Input value={form.contactPerson} onChange={(e) => setForm({ ...form, contactPerson: e.target.value })} /></Field>
          <Field label="Phone"><Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
          <Field label="Email"><Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
          <Field label="Credit days"><Input type="number" value={form.creditDays} onChange={(e) => setForm({ ...form, creditDays: Number(e.target.value) })} /></Field>
          {isOwner && (
            <Field label="Opening balance (INR)"><Input inputMode="decimal" value={form.openingBalance} onChange={(e) => setForm({ ...form, openingBalance: e.target.value })} /></Field>
          )}
          <Field label="Address" className="sm:col-span-2"><Textarea value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(editSupplier)}
        onClose={() => setEditSupplier(null)}
        title="Edit supplier"
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditSupplier(null)}>Cancel</Button>
            <Button variant="primary" loading={updateMutation.isPending} onClick={() => updateMutation.mutate()}>Save changes</Button>
          </>
        }
      >
        {editSupplier && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Name"><Input value={editSupplier.name} onChange={(e) => setEditSupplier({ ...editSupplier, name: e.target.value })} /></Field>
            <Field label="GSTIN"><Input value={editSupplier.gstin ?? ''} onChange={(e) => setEditSupplier({ ...editSupplier, gstin: e.target.value })} /></Field>
            <Field label="Contact person"><Input value={editSupplier.contactPerson ?? ''} onChange={(e) => setEditSupplier({ ...editSupplier, contactPerson: e.target.value })} /></Field>
            <Field label="Phone"><Input value={editSupplier.phone ?? ''} onChange={(e) => setEditSupplier({ ...editSupplier, phone: e.target.value })} /></Field>
            <Field label="Credit days"><Input type="number" value={editSupplier.creditDays} onChange={(e) => setEditSupplier({ ...editSupplier, creditDays: Number(e.target.value) })} /></Field>
            <Field label="Status">
              <Select value={editSupplier.isActive ? '1' : '0'} onChange={(e) => setEditSupplier({ ...editSupplier, isActive: Number(e.target.value) })}>
                <option value="1">Active</option>
                <option value="0">Inactive</option>
              </Select>
            </Field>
          </div>
        )}
      </Modal>

      <Modal
        open={Boolean(ledgerSupplier)}
        onClose={() => setLedgerSupplier(null)}
        title={ledgerSupplier ? `${ledgerSupplier.name} · supplier ledger` : 'Supplier ledger'}
        description={ledger.data ? `Opening ${formatINR(ledger.data.openingBalance)} · Closing payable ${formatINR(ledger.data.closingBalance)}` : undefined}
        size="xl"
        footer={
          <div className="flex w-full items-center justify-between">
            {can('payments.write') ? (
              <Button size="sm" variant="primary" onClick={() => setPaymentOpen(true)}>Record payment</Button>
            ) : <span />}
            <Button size="sm" variant="ghost" onClick={() => setLedgerSupplier(null)}>Close</Button>
          </div>
        }
      >
        {ledger.isLoading ? (
          <LoadingBlock rows={4} />
        ) : ledger.isError ? (
          <ErrorState error={ledger.error} onRetry={() => void ledger.refetch()} />
        ) : ledger.data ? (
          <div className="space-y-3">
            <TableScrollHint className="rounded-t-lg border border-b-0 border-carbon-700/60" />
            <div className="table-wrap rounded-b-lg border border-t-0 border-carbon-700/60">
              <table className="data-table min-w-[760px]">
                <thead>
                  <tr><th>Date</th><th>Type</th><th>Description</th><th className="text-right">Purchase</th><th className="text-right">Payment</th><th className="text-right">Balance</th></tr>
                </thead>
                <tbody>
                  {ledger.data.ledger.length === 0 ? (
                    <tr><td colSpan={6} className="py-8 text-center text-xs text-ink-500">No ledger activity.</td></tr>
                  ) : (
                    ledger.data.ledger.map((entry) => (
                      <tr key={entry.id}>
                        <td className="text-xs text-ink-400">{formatDate(String(entry.date))}</td>
                        <td><Badge tone={entry.type === 'PURCHASE' ? 'warning' : 'success'}>{entry.type}</Badge></td>
                        <td className="text-xs text-ink-300">{entry.description}</td>
                        <td className="num text-right">{Number(entry.debit) ? formatINR(entry.debit) : '—'}</td>
                        <td className="num text-right text-ok">{Number(entry.credit) ? formatINR(entry.credit) : '—'}</td>
                        <td className="num text-right">{formatINR(entry.runningBalance)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
            <p className="text-[11px] text-ink-500">
              Purchases are recognised when a delivery is received. Payments reduce the payable but do not alter the purchase invoice.
            </p>
          </div>
        ) : null}
      </Modal>

      {ledgerSupplier && (
        <SupplierPaymentModal
          open={paymentOpen}
          onClose={() => setPaymentOpen(false)}
          supplier={ledgerSupplier}
          stationId={stationId}
          onDone={() => { setPaymentOpen(false); invalidate(); }}
        />
      )}
    </>
  );
}

function SupplierPaymentModal({
  open,
  onClose,
  supplier,
  stationId,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  supplier: Supplier;
  stationId: string;
  onDone: () => void;
}) {
  const { toast } = useToast();
  const [amount, setAmount] = useState('');
  const [mode, setMode] = useState('BANK');
  const [reference, setReference] = useState('');
  const [occurredAt, setOccurredAt] = useState('');

  const payload = {
    stationId, direction: 'PAYOUT', partyType: 'SUPPLIER', supplierId: supplier.id,
    amount, mode, reference: reference || undefined,
    occurredAt: occurredAt || undefined,
  };
  const idempotency = useIdempotencyKey('payout', payload);
  const mutation = useMutation({
    mutationFn: () => apiPost('/finance/payments', payload, idempotency.key),
    onSuccess: () => {
      idempotency.reset();
      toast('Supplier payment recorded');
      onDone();
    },
    onError: (error) => toast('Payment failed', { tone: 'danger', description: (error as Error).message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`Supplier payment · ${supplier.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={mutation.isPending} disabled={!amount} onClick={() => mutation.mutate()}>Record payment</Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Amount (INR)" required><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label="Mode">
          <Select value={mode} onChange={(e) => setMode(e.target.value)}>
            {['BANK', 'UPI', 'CARD', 'CASH'].map((m) => <option key={m} value={m}>{m}</option>)}
          </Select>
        </Field>
        <Field label="Reference"><Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="NEFT/UTR reference" /></Field>
        <Field label="Payment date"><Input type="date" max={todayIst()} value={occurredAt} onChange={(e) => setOccurredAt(e.target.value)} /></Field>
      </div>
    </Modal>
  );
}
