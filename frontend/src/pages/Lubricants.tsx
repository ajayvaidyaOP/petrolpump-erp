import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Droplets, PackagePlus, Pencil, Plus, ShoppingCart, Truck } from 'lucide-react';
import { apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { useIdempotencyKey } from '../lib/idempotency';
import { useAuth, useStations } from '../lib/auth';
import { formatDateTime, formatINR, formatNumber } from '../lib/format';
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
  Pagination,
  Select,
  StatCard,
  TableScrollHint,
  useToast,
} from '../components/ui';

interface LubeProduct {
  id: string;
  code: string;
  name: string;
  brand: string | null;
  packSize: string;
  hsn: string | null;
  gstRatePct: string;
  purchaseRate: string;
  saleRate: string;
  stockQty: string;
  isActive: number;
}

interface LubeMovement {
  id: string;
  productId: string;
  productCode: string;
  productName: string;
  packSize: string;
  type: string;
  quantity: string;
  rate: string;
  amount: string;
  gstAmount: string;
  paymentMode: string | null;
  customerName: string | null;
  vehicleNumber: string | null;
  note: string | null;
  occurredAt: string;
}

interface CustomerOption {
  id: string;
  code: string;
  name: string;
}

type MovementType = 'PURCHASE' | 'SALE' | 'ADJUSTMENT';
const PAYMENT_MODES = ['CASH', 'UPI', 'CARD', 'CREDIT'];
const emptyProduct = {
  code: '',
  name: '',
  brand: '',
  packSize: '1 L',
  gstRatePct: '18',
  purchaseRate: '0',
  saleRate: '0',
};

export default function Lubricants() {
  const { can } = useAuth();
  const { station, stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [productOpen, setProductOpen] = useState(false);
  const [editing, setEditing] = useState<LubeProduct | null>(null);
  const [productForm, setProductForm] = useState(emptyProduct);
  const [moveType, setMoveType] = useState<MovementType | null>(null);
  const [moveForm, setMoveForm] = useState({
    productId: '',
    quantity: '',
    rate: '',
    paymentMode: 'CASH',
    customerId: '',
    vehicleNumber: '',
    note: '',
  });

  const products = useQuery<LubeProduct[]>({
    queryKey: ['lubricants', stationId],
    queryFn: () => apiGet<LubeProduct[]>(`/lubricants?stationId=${stationId}`),
    enabled: Boolean(stationId) && can('lubricants.read'),
  });
  const movements = useQuery<{ data: LubeMovement[]; meta: ListMeta }>({
    queryKey: ['lubricant-movements', stationId, page],
    queryFn: () =>
      apiList<LubeMovement>(`/lubricants/movements?stationId=${stationId}&page=${page}&pageSize=20`),
    enabled: Boolean(stationId) && can('lubricants.read'),
  });
  const customers = useQuery<CustomerOption[]>({
    queryKey: ['customers-min'],
    queryFn: async () => (await apiList<CustomerOption>('/customers?pageSize=100')).data,
    enabled: moveType === 'SALE' && can('customers.read'),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['lubricants'] });
    void queryClient.invalidateQueries({ queryKey: ['lubricant-movements'] });
  };

  const saveProduct = useMutation({
    mutationFn: () =>
      editing
        ? apiPatch(`/lubricants/${editing.id}`, {
            name: productForm.name,
            brand: productForm.brand || null,
            packSize: productForm.packSize,
            gstRatePct: productForm.gstRatePct,
            purchaseRate: productForm.purchaseRate,
            saleRate: productForm.saleRate,
          })
        : apiPost('/lubricants', { ...productForm, brand: productForm.brand || undefined }),
    onSuccess: () => {
      toast(editing ? 'Lubricant updated' : 'Lubricant item added');
      setProductOpen(false);
      setEditing(null);
      setProductForm(emptyProduct);
      invalidate();
    },
    onError: (error: Error) => toast('Could not save item', { tone: 'danger', description: error.message }),
  });

  const movementPayload = {
        stationId,
        productId: moveForm.productId,
        type: moveType,
        quantity: moveForm.quantity,
        rate: moveForm.rate || undefined,
        paymentMode: moveType === 'SALE' ? moveForm.paymentMode : undefined,
        customerId: moveType === 'SALE' && moveForm.paymentMode === 'CREDIT' ? moveForm.customerId : undefined,
        vehicleNumber: moveForm.vehicleNumber || undefined,
        note: moveForm.note || undefined,
      };
  const movementKey = useIdempotencyKey('lube-movement', movementPayload);
  const saveMovement = useMutation({
    mutationFn: () =>
      apiPost('/lubricants/movements', movementPayload, movementKey.key),
    onSuccess: () => {
      movementKey.reset();
      toast(moveType === 'SALE' ? 'Lubricant sale recorded' : 'Stock movement recorded');
      setMoveType(null);
      invalidate();
    },
    onError: (error: Error) => toast('Could not record movement', { tone: 'danger', description: error.message }),
  });

  if (!can('lubricants.read')) {
    return (
      <>
        <PageHeader title="Lubricants & 2T/4T oils" />
        <Card>
          <EmptyState
            title="Lubricants access required"
            description="Ask your organization owner to enable lubricants access."
            icon={<Droplets className="h-4 w-4" />}
          />
        </Card>
      </>
    );
  }

  const items = products.data ?? [];
  const stockValue = items.reduce((sum, p) => sum + Number(p.stockQty) * Number(p.purchaseRate), 0);
  const lowItems = items.filter((p) => Number(p.isActive) !== 0 && Number(p.stockQty) <= 5).length;

  const openMove = (type: MovementType, product?: LubeProduct) => {
    setMoveType(type);
    setMoveForm({
      productId: product?.id ?? '',
      quantity: '',
      rate: product ? (type === 'SALE' ? product.saleRate : product.purchaseRate) : '',
      paymentMode: 'CASH',
      customerId: '',
      vehicleNumber: '',
      note: '',
    });
  };

  return (
    <>
      <PageHeader
        title="Lubricants & packaged oils"
        description={`${station?.name ?? 'Station'} · SKU pack inventory, counter sales & GST-inclusive pricing`}
        actions={
          can('lubricants.write') ? (
            <>
              <Button
                variant="outline"
                size="sm"
                icon={<PackagePlus className="h-4 w-4" />}
                onClick={() => {
                  setEditing(null);
                  setProductForm({ ...emptyProduct });
                  setProductOpen(true);
                }}
              >
                New SKU
              </Button>
              <Button
                variant="primary"
                size="sm"
                icon={<ShoppingCart className="h-4 w-4" />}
                onClick={() => openMove('SALE')}
              >
                Record sale
              </Button>
            </>
          ) : undefined
        }
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="Catalog SKUs" value={items.length} hint="Packaged lubricants & additives" icon={<Droplets className="h-4 w-4" />} />
        <StatCard label="Inventory value (cost)" value={formatINR(stockValue)} hint="Valued at purchase rate" tone="accent" />
        <StatCard label="Low / out-of-stock SKUs" value={lowItems} hint="5 packs or fewer remaining" tone={lowItems > 0 ? 'warning' : 'success'} />
      </div>

      <Card>
        <CardHeader title="Pack inventory" subtitle="Current station stock, purchase cost, GST-inclusive MRP and per-pack margin." />
        <TableScrollHint />
        {products.isLoading ? (
          <LoadingBlock rows={4} />
        ) : products.isError ? (
          <div className="p-4">
            <ErrorState error={products.error} onRetry={() => void products.refetch()} />
          </div>
        ) : items.length === 0 ? (
          <EmptyState
            title="No lubricant SKUs configured"
            description="Add engine oils, coolant and 2T/4T pouches to track counter stock and GST sales."
            icon={<Droplets className="h-4 w-4" />}
            action={
              can('lubricants.write') ? (
                <Button size="sm" variant="primary" onClick={() => setProductOpen(true)}>
                  Add first SKU
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="table-wrap">
            <table className="data-table min-w-[760px]">
              <thead>
                <tr>
                  <th>SKU / Item</th>
                  <th>Pack</th>
                  <th className="text-right">Stock (packs)</th>
                  <th className="text-right">Cost ₹</th>
                  <th className="text-right">MRP ₹ (GST incl.)</th>
                  <th className="text-right">Margin ₹</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((product) => {
                  const qty = Number(product.stockQty);
                  return (
                    <tr key={product.id} className={Number(product.isActive) === 0 ? 'opacity-50' : undefined}>
                      <td>
                        <p className="font-semibold text-ink-100">{product.name}</p>
                        <p className="text-[10px] text-ink-500">
                          {product.code}
                          {product.brand ? ` · ${product.brand}` : ''} · GST {product.gstRatePct}%
                        </p>
                      </td>
                      <td className="text-xs">{product.packSize}</td>
                      <td className="num text-right">
                        <span
                          className={
                            qty <= 0
                              ? 'font-bold text-danger'
                              : qty <= 5
                                ? 'font-semibold text-warn'
                                : 'font-medium text-ink-100'
                          }
                        >
                          {formatNumber(qty, 0)}
                        </span>
                        {qty <= 0 && (
                          <Badge tone="danger" className="ml-2">
                            Out
                          </Badge>
                        )}
                        {qty > 0 && qty <= 5 && (
                          <Badge tone="warning" className="ml-2">
                            Low
                          </Badge>
                        )}
                      </td>
                      <td className="num text-right">{formatINR(product.purchaseRate)}</td>
                      <td className="num text-right font-medium text-ink-100">{formatINR(product.saleRate)}</td>
                      <td className="num text-right text-xs font-semibold text-ok">
                        {formatINR(Number(product.saleRate) - Number(product.purchaseRate))}
                      </td>
                      <td className="whitespace-nowrap text-right">
                        {can('lubricants.write') && (
                          <>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => openMove('PURCHASE', product)}
                              icon={<Truck className="h-3.5 w-3.5" />}
                            >
                              Receive
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => openMove('SALE', product)}
                              icon={<ShoppingCart className="h-3.5 w-3.5" />}
                            >
                              Sell
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              aria-label="Edit item"
                              onClick={() => {
                                setEditing(product);
                                setProductForm({
                                  code: product.code,
                                  name: product.name,
                                  brand: product.brand ?? '',
                                  packSize: product.packSize,
                                  gstRatePct: product.gstRatePct,
                                  purchaseRate: product.purchaseRate,
                                  saleRate: product.saleRate,
                                });
                                setProductOpen(true);
                              }}
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card className="mt-4">
        <CardHeader title="Recent stock & counter movements" subtitle="Purchases, retail counter sales and adjustments." />
        <TableScrollHint />
        {movements.isLoading ? (
          <LoadingBlock rows={4} />
        ) : movements.isError ? (
          <div className="p-4">
            <ErrorState error={movements.error} onRetry={() => void movements.refetch()} />
          </div>
        ) : (movements.data?.data ?? []).length === 0 ? (
          <EmptyState
            title="No lubricant movements yet"
            description="Receipts and counter sales appear here automatically."
            icon={<Droplets className="h-4 w-4" />}
          />
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table min-w-[720px]">
                <thead>
                  <tr>
                    <th>Timestamp</th>
                    <th>SKU</th>
                    <th>Type</th>
                    <th className="text-right">Qty</th>
                    <th className="text-right">Amount</th>
                    <th>Payment / Fleet customer</th>
                  </tr>
                </thead>
                <tbody>
                  {(movements.data?.data ?? []).map((move) => (
                    <tr key={move.id}>
                      <td className="whitespace-nowrap text-xs">{formatDateTime(move.occurredAt)}</td>
                      <td className="text-xs font-medium text-ink-100">
                        {move.productName} <span className="font-normal text-ink-500">({move.packSize})</span>
                      </td>
                      <td>
                        <Badge tone={move.type === 'SALE' ? 'accent' : move.type === 'PURCHASE' ? 'success' : 'neutral'}>
                          {move.type}
                        </Badge>
                      </td>
                      <td className="num text-right">{formatNumber(move.quantity, 0)}</td>
                      <td className="num text-right font-medium text-ink-100">{formatINR(move.amount)}</td>
                      <td className="text-xs text-ink-400">
                        {move.paymentMode ?? '—'}
                        {move.customerName ? ` · ${move.customerName}` : ''}
                        {move.vehicleNumber ? ` · ${move.vehicleNumber}` : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {movements.data && (
              <Pagination
                page={movements.data.meta.page}
                pageSize={movements.data.meta.pageSize}
                total={movements.data.meta.total}
                totalPages={movements.data.meta.totalPages}
                onPage={setPage}
              />
            )}
          </>
        )}
      </Card>

      <Modal
        open={productOpen}
        onClose={() => setProductOpen(false)}
        title={editing ? 'Edit lubricant SKU' : 'New lubricant SKU'}
        description="Enter sale rate inclusive of GST (MRP). Taxable value and GST split automatically in exports."
        footer={
          <>
            <Button variant="ghost" onClick={() => setProductOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={saveProduct.isPending}
              disabled={!productForm.name || (!editing && !productForm.code)}
              onClick={() => saveProduct.mutate()}
            >
              {editing ? 'Save changes' : 'Add SKU'}
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          {!editing && (
            <Field label="SKU code" required hint="e.g. SERVO-4T-900">
              <Input
                value={productForm.code}
                onChange={(e) => setProductForm({ ...productForm, code: e.target.value.toUpperCase() })}
              />
            </Field>
          )}
          <Field label="Product name" required>
            <Input value={productForm.name} onChange={(e) => setProductForm({ ...productForm, name: e.target.value })} />
          </Field>
          <Field label="Brand">
            <Input value={productForm.brand} onChange={(e) => setProductForm({ ...productForm, brand: e.target.value })} />
          </Field>
          <Field label="Pack size" required>
            <Input value={productForm.packSize} onChange={(e) => setProductForm({ ...productForm, packSize: e.target.value })} />
          </Field>
          <Field label="GST %">
            <Input inputMode="decimal" value={productForm.gstRatePct} onChange={(e) => setProductForm({ ...productForm, gstRatePct: e.target.value })} />
          </Field>
          <Field label="Purchase cost (INR)">
            <Input inputMode="decimal" value={productForm.purchaseRate} onChange={(e) => setProductForm({ ...productForm, purchaseRate: e.target.value })} />
          </Field>
          <Field label="Sale MRP (INR)">
            <Input inputMode="decimal" value={productForm.saleRate} onChange={(e) => setProductForm({ ...productForm, saleRate: e.target.value })} />
          </Field>
        </div>
      </Modal>

      <Modal
        open={moveType !== null}
        onClose={() => setMoveType(null)}
        title={moveType === 'SALE' ? 'Record lubricant sale' : moveType === 'PURCHASE' ? 'Receive lubricant stock' : 'Stock adjustment'}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setMoveType(null)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={saveMovement.isPending}
              disabled={!moveForm.productId || !moveForm.quantity}
              onClick={() => saveMovement.mutate()}
            >
              Save
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Field label="SKU" required>
            <Select
              value={moveForm.productId}
              onChange={(e) => {
                const product = items.find((p) => p.id === e.target.value);
                setMoveForm({
                  ...moveForm,
                  productId: e.target.value,
                  rate: product ? (moveType === 'SALE' ? product.saleRate : product.purchaseRate) : moveForm.rate,
                });
              }}
            >
              <option value="">Select SKU…</option>
              {items.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.packSize}) — stock {formatNumber(p.stockQty, 0)}
                </option>
              ))}
            </Select>
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Quantity (packs)" required>
              <Input inputMode="decimal" value={moveForm.quantity} onChange={(e) => setMoveForm({ ...moveForm, quantity: e.target.value })} />
            </Field>
            <Field label="Rate (INR / pack)">
              <Input inputMode="decimal" value={moveForm.rate} onChange={(e) => setMoveForm({ ...moveForm, rate: e.target.value })} />
            </Field>
          </div>
          {moveType === 'SALE' && (
            <>
              <Field label="Payment mode" required>
                <Select value={moveForm.paymentMode} onChange={(e) => setMoveForm({ ...moveForm, paymentMode: e.target.value })}>
                  {PAYMENT_MODES.map((mode) => (
                    <option key={mode} value={mode}>
                      {mode}
                    </option>
                  ))}
                </Select>
              </Field>
              {moveForm.paymentMode === 'CREDIT' && (
                <Field label="Credit customer" required>
                  <Select value={moveForm.customerId} onChange={(e) => setMoveForm({ ...moveForm, customerId: e.target.value })}>
                    <option value="">Select customer…</option>
                    {(customers.data ?? []).map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.code} · {c.name}
                      </option>
                    ))}
                  </Select>
                </Field>
              )}
              <Field label="Vehicle registration">
                <Input
                  placeholder="MH12AB1234"
                  value={moveForm.vehicleNumber}
                  onChange={(e) => setMoveForm({ ...moveForm, vehicleNumber: e.target.value.toUpperCase() })}
                />
              </Field>
            </>
          )}
          <Field label="Note">
            <Input value={moveForm.note} onChange={(e) => setMoveForm({ ...moveForm, note: e.target.value })} />
          </Field>
          {moveForm.quantity && moveForm.rate && (
            <p className="rounded-lg border border-carbon-700/75 bg-carbon-900/55 px-3 py-2 text-sm text-ink-300">
              Total amount:{' '}
              <strong className="num font-semibold text-ink-100">
                {formatINR(Number(moveForm.quantity) * Number(moveForm.rate))}
              </strong>
            </p>
          )}
        </div>
      </Modal>

      <div className="mt-4 flex items-center gap-2 text-[11px] text-ink-500">
        <Plus className="h-3 w-3 text-accent" /> Monthly GST-ready lubricant statements can be downloaded under Reports → Accounting Exports.
      </div>
    </>
  );
}
