import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDownLeft, ArrowUpRight, Banknote, Plus, Receipt, RotateCcw, Wallet } from 'lucide-react';
import { apiDelete, apiGet, apiGetWithMeta, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useIdempotencyKey } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDate, formatDateTime, formatINR, todayIst } from '../lib/format';
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
  StatusBadge,
  Tabs,
  Textarea,
  useToast,
} from '../components/ui';

interface Payment {
  id: string;
  direction: 'RECEIPT' | 'PAYOUT';
  partyType: string;
  customerName: string | null;
  supplierName: string | null;
  amount: string;
  mode: string;
  reference: string | null;
  occurredAt: string;
  status: string;
  settlementRef: string | null;
  settledAt: string | null;
  notes: string | null;
  createdByName: string | null;
}

interface Expense {
  id: string;
  categoryName: string;
  description: string;
  amount: string;
  occurredAt: string;
  paidVia: string;
  reference: string | null;
  createdByName: string | null;
}

interface Customer { id: string; name: string }
interface Supplier { id: string; name: string }
interface Category { id: string; name: string; isActive: number }

interface ReconSummary {
  expected: {
    cash: string;
    digital: string;
    cashSales: string;
    digitalSales: string;
    creditSales: string;
    cashReceipts: string;
    digitalReceipts: string;
    cashExpenses: string;
    cashPayouts: string;
    shiftAggregateValue: string;
  };
}

export default function Finance() {
  const { can } = useAuth();
  const { stationId, station } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState('payments');
  const [date, setDate] = useState(todayIst());
  const [page, setPage] = useState(1);
  const [direction, setDirection] = useState('');
  const [mode, setMode] = useState('');

  const [paymentOpen, setPaymentOpen] = useState(false);
  const [settlePayment, setSettlePayment] = useState<Payment | null>(null);
  const [reversePayment, setReversePayment] = useState<Payment | null>(null);
  const [expenseOpen, setExpenseOpen] = useState(false);
  const [deleteExpense, setDeleteExpense] = useState<Expense | null>(null);
  useEffect(() => {
    if (searchParams.get('new') === 'expense' && can('expenses.write')) {
      setTab('expenses'); setExpenseOpen(true);
    }
  }, [searchParams, can]);
  const closeExpense = () => {
    setExpenseOpen(false);
    if (searchParams.has('new')) {
      const next = new URLSearchParams(searchParams); next.delete('new');
      setSearchParams(next, { replace: true });
    }
  };

  const summary = useQuery({
    queryKey: ['finance-summary', stationId, date],
    queryFn: () => apiGet<ReconSummary>(`/finance/reconciliation?stationId=${stationId}&businessDate=${date}`),
    enabled: Boolean(stationId) && can('reconciliation.read'),
  });

  const payments = useQuery({
    queryKey: ['payments', stationId, date, page, direction, mode],
    queryFn: () =>
      apiList<Payment>(
        `/finance/payments?stationId=${stationId}&from=${date}&to=${date}&page=${page}&pageSize=15${direction ? `&direction=${direction}` : ''}${mode ? `&mode=${mode}` : ''}`,
      ),
    enabled: Boolean(stationId) && tab === 'payments',
  });

  const expenses = useQuery({
    queryKey: ['expenses', stationId, date, page],
    queryFn: async () => {
      const response = await apiGetWithMeta<{ expenses: Expense[]; totalAmount: string }>(
        `/finance/expenses?stationId=${stationId}&from=${date}&to=${date}&page=${page}&pageSize=15`,
      );
      return { data: response.data.expenses, meta: response.meta };
    },
    enabled: Boolean(stationId) && tab === 'expenses',
  });

  const customers = useQuery({
    queryKey: ['customers-lite'],
    queryFn: () => apiGet<Customer[]>('/catalog/customers?pageSize=100'),
    enabled: can('customers.read'),
  });
  const suppliers = useQuery({
    queryKey: ['suppliers-lite'],
    queryFn: () => apiGet<Supplier[]>('/catalog/suppliers?pageSize=100'),
    enabled: can('suppliers.read'),
  });
  const categories = useQuery({
    queryKey: ['expense-categories'],
    queryFn: () => apiGet<Category[]>('/catalog/expense-categories?pageSize=100'),
    enabled: tab === 'expenses' || expenseOpen,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['payments'] });
    void queryClient.invalidateQueries({ queryKey: ['expenses'] });
    void queryClient.invalidateQueries({ queryKey: ['finance-summary'] });
    void queryClient.invalidateQueries({ queryKey: ['customer-ledger'] });
    void queryClient.invalidateQueries({ queryKey: ['customers'] });
    void queryClient.invalidateQueries({ queryKey: ['supplier-ledger'] });
    void queryClient.invalidateQueries({ queryKey: ['suppliers'] });
    void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const settleMutation = useMutation({
    mutationFn: ({ id, settlementRef }: { id: string; settlementRef: string }) =>
      apiPatch(`/finance/payments/${id}/settle`, { settlementRef }),
    onSuccess: () => { toast('Settlement recorded'); setSettlePayment(null); invalidate(); },
    onError: (e) => toast('Settlement failed', { tone: 'danger', description: (e as Error).message }),
  });

  const reverseMutation = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => apiPost(`/finance/payments/${id}/reverse`, { reason }),
    onSuccess: () => { toast('Payment reversed', { tone: 'warning' }); setReversePayment(null); invalidate(); },
    onError: (e) => toast('Reversal failed', { tone: 'danger', description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => apiDelete(`/finance/expenses/${id}`),
    onSuccess: () => { toast('Expense deleted'); setDeleteExpense(null); invalidate(); },
    onError: (e) => toast('Delete failed', { tone: 'danger', description: (e as Error).message }),
  });

  if (!stationId) return <EmptyState title="No station assigned" />;

  const paymentRows = payments.data?.data ?? [];
  const paymentMeta: ListMeta = payments.data?.meta ?? { page: 1, pageSize: 15, total: 0, totalPages: 1 };
  const expenseRows = expenses.data?.data ?? [];
  const expenseMeta: ListMeta = expenses.data?.meta ?? { page: 1, pageSize: 15, total: 0, totalPages: 1 };
  const expected = summary.data?.expected;

  return (
    <>
      <PageHeader
        title="Payments & expenses"
        description={`${station?.name ?? ''} · collections, supplier payouts, expenses and settlement tracking`}
        actions={
          <>
            <ExportCsvButton path={`/finance/payments?stationId=${stationId}&businessDate=${date}`} filename="petroflow-payments" />
            <ExportCsvButton path={`/finance/expenses?stationId=${stationId}&businessDate=${date}`} filename="petroflow-expenses" label="Expenses CSV" />
            <Field label="Business date" className="mb-0">
              <Input type="date" value={date} onChange={(e) => { setDate(e.target.value); setPage(1); }} className="w-auto" />
            </Field>
            {can('expenses.write') && (
              <Button size="sm" variant="secondary" icon={<Receipt className="h-4 w-4" />} onClick={() => setExpenseOpen(true)}>Add expense</Button>
            )}
            {can('payments.write') && (
              <Button size="sm" variant="primary" icon={<Plus className="h-4 w-4" />} onClick={() => setPaymentOpen(true)}>Record payment</Button>
            )}
          </>
        }
      />

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Expected cash" value={formatINR(expected?.cash ?? 0, true)} hint="Cash sales + cash receipts − cash payouts − cash expenses" icon={<Wallet className="h-4 w-4" />} />
        <StatCard label="Expected digital" value={formatINR(expected?.digital ?? 0, true)} hint="UPI, card and bank collections" icon={<Banknote className="h-4 w-4" />} />
        <StatCard label="Credit sales on account" value={formatINR(expected?.creditSales ?? 0, true)} hint="Not counted as cash received" icon={<ArrowUpRight className="h-4 w-4" />} tone="warning" />
        <StatCard label="Aggregate shift estimates" value={formatINR(expected?.shiftAggregateValue ?? 0, true)} hint="Totalizer totals without a payment split" icon={<Receipt className="h-4 w-4" />} />
      </div>

      <div className="mt-3">
        <Tabs tabs={[{ key: 'payments', label: 'Payments' }, { key: 'expenses', label: 'Expenses' }]} active={tab} onChange={(k) => { setTab(k); setPage(1); }} />
      </div>

      {tab === 'payments' ? (
        <Card className="mt-3">
          <CardHeader
            title="Payment register"
            subtitle="Recorded payments are distinct from settlement. Cash is settled at collection; digital needs a settlement reference."
            action={
              <div className="flex gap-2 max-sm:w-full">
                <Select value={direction} onChange={(e) => { setDirection(e.target.value); setPage(1); }} className="h-9 w-[calc(50%-0.25rem)] py-1 text-xs sm:h-8 sm:w-36">
                  <option value="">All directions</option>
                  <option value="RECEIPT">Receipts</option>
                  <option value="PAYOUT">Payouts</option>
                </Select>
                <Select value={mode} onChange={(e) => { setMode(e.target.value); setPage(1); }} className="h-9 w-[calc(50%-0.25rem)] py-1 text-xs sm:h-8 sm:w-32">
                  <option value="">All modes</option>
                  {['CASH', 'UPI', 'CARD', 'BANK'].map((m) => <option key={m} value={m}>{m}</option>)}
                </Select>
              </div>
            }
          />
          {payments.isLoading ? (
            <LoadingBlock rows={4} />
          ) : payments.isError ? (
            <div className="p-4"><ErrorState error={payments.error} onRetry={() => void payments.refetch()} /></div>
          ) : paymentRows.length === 0 ? (
            <EmptyState title="No payments on this date" description="Record a customer receipt or supplier payout." icon={<Banknote className="h-4 w-4" />} />
          ) : (
            <>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr><th>Date</th><th>Party</th><th>Direction</th><th>Mode</th><th className="text-right">Amount</th><th>Settlement</th><th>Status</th><th /></tr>
                  </thead>
                  <tbody>
                    {paymentRows.map((payment) => (
                      <tr key={payment.id}>
                        <td className="text-xs text-ink-400">{formatDateTime(payment.occurredAt)}</td>
                        <td>
                          <p className="text-sm text-ink-200">{payment.customerName ?? payment.supplierName ?? '—'}</p>
                          <p className="text-[10px] text-ink-500">{payment.reference ?? 'no reference'}</p>
                        </td>
                        <td>
                          <Badge tone={payment.direction === 'RECEIPT' ? 'success' : 'warning'}>
                            {payment.direction === 'RECEIPT' ? <ArrowDownLeft className="h-3 w-3" /> : <ArrowUpRight className="h-3 w-3" />}
                            {payment.direction}
                          </Badge>
                        </td>
                        <td className="text-xs text-ink-300">{payment.mode}</td>
                        <td className={`num text-right ${payment.direction === 'RECEIPT' ? 'text-ok' : 'text-warn'}`}>{formatINR(payment.amount)}</td>
                        <td className="text-xs text-ink-500">{payment.settlementRef ?? (payment.mode === 'CASH' ? 'cash' : 'not settled')}</td>
                        <td><StatusBadge status={payment.status} /></td>
                        <td className="text-right">
                          <div className="flex justify-end gap-1">
                            {can('payments.write') && payment.mode !== 'CASH' && payment.status === 'POSTED' && (
                              <Button size="sm" variant="ghost" onClick={() => setSettlePayment(payment)}>Settle</Button>
                            )}
                            {can('payments.write') && payment.status !== 'REVERSED' && (
                              <Button size="sm" variant="ghost" onClick={() => setReversePayment(payment)}>Reverse</Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination {...paymentMeta} onPage={setPage} />
            </>
          )}
        </Card>
      ) : (
        <Card className="mt-3">
          <CardHeader title="Expenses" subtitle="Operating costs recorded against this station" />
          {expenses.isLoading ? (
            <LoadingBlock rows={4} />
          ) : expenses.isError ? (
            <div className="p-4"><ErrorState error={expenses.error} onRetry={() => void expenses.refetch()} /></div>
          ) : expenseRows.length === 0 ? (
            <EmptyState title="No expenses recorded" description="Record electricity, salaries, maintenance and other costs." icon={<Receipt className="h-4 w-4" />} />
          ) : (
            <>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr><th>Date</th><th>Category</th><th>Description</th><th>Paid via</th><th className="text-right">Amount</th><th /></tr>
                  </thead>
                  <tbody>
                    {expenseRows.map((expense) => (
                      <tr key={expense.id}>
                        <td className="text-xs text-ink-400">{formatDateTime(expense.occurredAt)}</td>
                        <td><Badge tone="neutral">{expense.categoryName}</Badge></td>
                        <td>
                          <p className="text-sm text-ink-200">{expense.description}</p>
                          <p className="text-[10px] text-ink-500">{expense.reference ?? ''} {expense.createdByName ? `· ${expense.createdByName}` : ''}</p>
                        </td>
                        <td className="text-xs text-ink-300">{expense.paidVia}</td>
                        <td className="num text-right text-danger">{formatINR(expense.amount)}</td>
                        <td className="text-right">
                          {can('expenses.write') && <Button size="sm" variant="ghost" onClick={() => setDeleteExpense(expense)}>Delete</Button>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination {...expenseMeta} onPage={setPage} />
            </>
          )}
        </Card>
      )}

      <PaymentModal
        open={paymentOpen}
        onClose={() => setPaymentOpen(false)}
        stationId={stationId}
        customers={customers.data ?? []}
        suppliers={suppliers.data ?? []}
        onDone={() => { setPaymentOpen(false); invalidate(); }}
      />

      <ExpenseModal
        open={expenseOpen}
        onClose={closeExpense}
        stationId={stationId}
        categories={categories.data ?? []}
        onDone={() => { closeExpense(); invalidate(); }}
      />

      {settlePayment && <SettleModal payment={settlePayment} onClose={() => setSettlePayment(null)} loading={settleMutation.isPending} onSubmit={(ref) => settleMutation.mutate({ id: settlePayment.id, settlementRef: ref })} />}
      {reversePayment && <ReversePaymentModal payment={reversePayment} onClose={() => setReversePayment(null)} loading={reverseMutation.isPending} onSubmit={(reason) => reverseMutation.mutate({ id: reversePayment.id, reason })} />}
      <ConfirmDialog
        open={Boolean(deleteExpense)}
        onClose={() => setDeleteExpense(null)}
        onConfirm={() => deleteExpense && deleteMutation.mutate(deleteExpense.id)}
        title="Delete expense"
        message={`Delete “${deleteExpense?.description}” for ${formatINR(deleteExpense?.amount ?? 0)}? The deletion is audited and cannot be undone.`}
        confirmLabel="Delete expense"
        danger
        loading={deleteMutation.isPending}
      />
    </>
  );
}

function PaymentModal({
  open,
  onClose,
  stationId,
  customers,
  suppliers,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  stationId: string;
  customers: Customer[];
  suppliers: Supplier[];
  onDone: () => void;
}) {
  const { toast } = useToast();
  const [direction, setDirection] = useState<'RECEIPT' | 'PAYOUT'>('RECEIPT');
  const [customerId, setCustomerId] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [amount, setAmount] = useState('');
  const [mode, setMode] = useState('CASH');
  const [reference, setReference] = useState('');
  const [notes, setNotes] = useState('');
  const payload = {
    stationId, direction,
    partyType: direction === 'RECEIPT' ? 'CUSTOMER' : 'SUPPLIER',
    customerId: direction === 'RECEIPT' ? customerId : undefined,
    supplierId: direction === 'PAYOUT' ? supplierId : undefined,
    amount, mode, reference: reference || undefined, notes: notes || undefined,
  };
  const idempotency = useIdempotencyKey('payment', payload);

  const mutation = useMutation({
    mutationFn: () => apiPost('/finance/payments', payload, idempotency.key),
    onSuccess: () => {
      idempotency.reset();
      toast('Payment recorded');
      setAmount('');
      setReference('');
      onDone();
    },
    onError: (e) => toast('Payment failed', { tone: 'danger', description: (e as Error).message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Record payment"
      description="Receipts reduce customer credit; payouts settle supplier payables."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            loading={mutation.isPending}
            disabled={!amount || (direction === 'RECEIPT' ? !customerId : !supplierId)}
            onClick={() => mutation.mutate()}
          >
            Save payment
          </Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Direction">
          <Select value={direction} onChange={(e) => setDirection(e.target.value as 'RECEIPT' | 'PAYOUT')}>
            <option value="RECEIPT">Customer receipt</option>
            <option value="PAYOUT">Supplier payout</option>
          </Select>
        </Field>
        {direction === 'RECEIPT' ? (
          <Field label="Customer" required>
            <Select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
              <option value="">Select customer…</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </Select>
          </Field>
        ) : (
          <Field label="Supplier" required>
            <Select value={supplierId} onChange={(e) => setSupplierId(e.target.value)}>
              <option value="">Select supplier…</option>
              {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select>
          </Field>
        )}
        <Field label="Amount (INR)" required><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label="Mode">
          <Select value={mode} onChange={(e) => setMode(e.target.value)}>
            {['CASH', 'UPI', 'CARD', 'BANK'].map((m) => <option key={m} value={m}>{m}</option>)}
          </Select>
        </Field>
        <Field label="Reference"><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
        <Field label="Notes"><Input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
      </div>
    </Modal>
  );
}

function ExpenseModal({
  open,
  onClose,
  stationId,
  categories,
  onDone,
}: {
  open: boolean;
  onClose: () => void;
  stationId: string;
  categories: Category[];
  onDone: () => void;
}) {
  const { toast } = useToast();
  const [categoryId, setCategoryId] = useState('');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [paidVia, setPaidVia] = useState('CASH');
  const [reference, setReference] = useState('');
  const payload = {
    stationId, categoryId: categoryId || undefined,
    description, amount, paidVia, reference: reference || undefined,
  };
  const idempotency = useIdempotencyKey('expense', payload);
  const mutation = useMutation({
    mutationFn: () => apiPost('/finance/expenses', payload, idempotency.key),
    onSuccess: () => {
      idempotency.reset();
      toast('Expense recorded');
      setDescription('');
      setAmount('');
      setReference('');
      onDone();
    },
    onError: (e) => toast('Could not save expense', { tone: 'danger', description: (e as Error).message }),
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Record expense"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={mutation.isPending} disabled={!description || !amount} onClick={() => mutation.mutate()}>Save expense</Button>
        </>
      }
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Category">
          <Select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">General</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
        </Field>
        <Field label="Paid via">
          <Select value={paidVia} onChange={(e) => setPaidVia(e.target.value)}>
            {['CASH', 'UPI', 'CARD', 'BANK', 'CHEQUE'].map((m) => <option key={m} value={m}>{m}</option>)}
          </Select>
        </Field>
        <Field label="Description" required className="sm:col-span-2">
          <Textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder="e.g. MSEDCL commercial electricity bill" />
        </Field>
        <Field label="Amount (INR)" required><Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
        <Field label="Supporting reference"><Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Bill number / voucher" /></Field>
      </div>
    </Modal>
  );
}

function SettleModal({
  payment,
  onClose,
  loading,
  onSubmit,
}: {
  payment: Payment;
  onClose: () => void;
  loading: boolean;
  onSubmit: (ref: string) => void;
}) {
  const [ref, setRef] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title="Record settlement"
      description={`${payment.mode} ${formatINR(payment.amount)} · ${payment.customerName ?? payment.supplierName ?? ''}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={loading} disabled={ref.trim().length < 2} onClick={() => onSubmit(ref.trim())}>Mark settled</Button>
        </>
      }
    >
      <Field label="Settlement / UTR reference" required hint="Bank or payment gateway reference confirming funds received.">
        <Input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="e.g. UPI-SETTLE-88231" />
      </Field>
    </Modal>
  );
}

function ReversePaymentModal({
  payment,
  onClose,
  loading,
  onSubmit,
}: {
  payment: Payment;
  onClose: () => void;
  loading: boolean;
  onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  return (
    <Modal
      open
      onClose={onClose}
      title="Reverse payment"
      description="A reversal creates an offsetting ledger entry and is audited."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" loading={loading} disabled={reason.trim().length < 5} onClick={() => onSubmit(reason.trim())}>
            <RotateCcw className="h-4 w-4" /> Reverse
          </Button>
        </>
      }
    >
      <Field label="Reason" required>
        <Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Duplicate entry, wrong amount, cheque bounce…" />
      </Field>
      <p className="mt-2 text-[11px] text-ink-500">Reversing {payment.direction.toLowerCase()} of {formatINR(payment.amount)}.</p>
    </Modal>
  );
}
