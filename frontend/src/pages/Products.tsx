import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fuel, Pencil, Plus, Tags, TrendingUp } from 'lucide-react';
import { apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { useAuth, useStations } from '../lib/auth';
import { DeleteRecordButton, ExportCsvButton } from '../components/OwnerActions';
import { formatDateTime, formatINR } from '../lib/format';
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
  SearchInput,
  Select,
  StatCard,
  StatusBadge,
  useToast,
} from '../components/ui';

interface Product {
  id: string;
  code: string;
  name: string;
  category: string;
  unit: string;
  color: string;
  isActive: number;
  createdAt: string;
}

interface PriceRow {
  id: string;
  productId: string;
  productName: string;
  productCode: string;
  stationId: string | null;
  stationName: string | null;
  ratePerLitre: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string | null;
  createdAt: string;
}

const emptyProduct = { code: '', name: '', category: 'PETROL', unit: 'L', color: '#067647' };

export default function Products() {
  const { can } = useAuth();
  const { stationId } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({ ...emptyProduct });
  const [edit, setEdit] = useState<Product | null>(null);
  const [editForm, setEditForm] = useState({ name: '', category: 'PETROL', color: '#067647', isActive: true });
  const [priceFor, setPriceFor] = useState<Product | null>(null);
  const [priceForm, setPriceForm] = useState({ rate: '', effectiveFrom: '', note: '', scope: 'STATION' });

  const products = useQuery<{ data: Product[]; meta: ListMeta }>({
    queryKey: ['products', page, search],
    queryFn: () => apiList<Product>(`/catalog/products${search ? `?search=${encodeURIComponent(search)}&page=${page}` : `?page=${page}`}`),
  });

  const prices = useQuery<PriceRow[]>({
    queryKey: ['prices', stationId],
    queryFn: () => apiGet<PriceRow[]>(`/catalog/prices?stationId=${stationId}`),
    enabled: Boolean(stationId),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
    void queryClient.invalidateQueries({ queryKey: ['prices'] });
  };

  const create = useMutation({
    mutationFn: () => apiPost<Product>('/catalog/products', { ...form, isActive: true }),
    onSuccess: () => {
      toast('Product created', { tone: 'success', description: `${form.name} is available for tanks, nozzles and pricing.` });
      setCreateOpen(false);
      setForm({ ...emptyProduct });
      invalidate();
    },
    onError: (error: Error) => toast('Could not create product', { tone: 'danger', description: error.message }),
  });

  const update = useMutation({
    mutationFn: () => apiPatch<Product>(`/catalog/products/${edit?.id}`, editForm),
    onSuccess: () => {
      toast('Product updated', { tone: 'success' });
      setEdit(null);
      invalidate();
    },
    onError: (error: Error) => toast('Update failed', { tone: 'danger', description: error.message }),
  });

  const addPrice = useMutation({
    mutationFn: () =>
      apiPost<PriceRow>('/catalog/prices', {
        productId: priceFor?.id,
        stationId: priceForm.scope === 'STATION' ? stationId : null,
        rate: priceForm.rate,
        effectiveFrom: priceForm.effectiveFrom || undefined,
        note: priceForm.note || undefined,
      }),
    onSuccess: () => {
      toast('Price recorded', {
        tone: 'success',
        description: 'The previous open-ended price period is closed at the new effective timestamp.',
      });
      setPriceFor(null);
      setPriceForm({ rate: '', effectiveFrom: '', note: '', scope: 'STATION' });
      invalidate();
    },
    onError: (error: Error) => toast('Could not record price', { tone: 'danger', description: error.message }),
  });

  const rows = products.data?.data ?? [];
  const currentRate = useMemo(() => {
    const map = new Map<string, PriceRow>();
    for (const p of prices.data ?? []) {
      if (p.stationId && p.stationId !== stationId) continue;
      if (!p.effectiveTo && !map.has(p.productId)) map.set(p.productId, p);
    }
    return map;
  }, [prices.data, stationId]);

  return (
    <div>
      <PageHeader
        title="Products & Prices"
        description="Fuel grades, units and effective-dated prices. Every sale is valued with the price in force at the time of the transaction — never a later price."
        actions={
          <>
            <ExportCsvButton path="/catalog/products" filename="petroflow-products" />
            {can('products.write') ? (
              <Button variant="primary" size="sm" onClick={() => setCreateOpen(true)}>
                <Plus className="h-4 w-4" /> New product
              </Button>
            ) : null}
          </>
        }
      />

      <div className="mb-4 grid gap-3 sm:grid-cols-3">
        <StatCard label="Products" value={products.data?.meta.total ?? rows.length} icon={<PackageIcon />} />
        <StatCard
          label="Price records"
          value={prices.data?.length ?? 0}
          hint="Open-ended rows are current; historical rows are immutable."
          icon={<Tags className="h-4 w-4" />}
        />
        <StatCard
          label="Priced products"
          value={currentRate.size}
          tone={currentRate.size === 0 ? 'warning' : 'success'}
          hint={currentRate.size === 0 ? 'Record a price before closing a shift.' : 'A price is in force for every product sold.'}
          icon={<TrendingUp className="h-4 w-4" />}
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-5">
        <Card className="xl:col-span-3">
          <CardHeader
            title="Fuel products"
            subtitle="Products are shared across your stations; price records can be station-specific."
            action={<SearchInput value={search} onChange={(v) => { setSearch(v); setPage(1); }} placeholder="Search code or name" className="w-full sm:w-56" />}
          />
          {products.isLoading ? (
            <LoadingBlock />
          ) : products.isError ? (
            <ErrorState error={products.error} onRetry={() => void products.refetch()} />
          ) : rows.length === 0 ? (
            <EmptyState title="No products" description="Add petrol, diesel or lubricant products to begin." />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Product</th>
                      <th>Category</th>
                      <th className="text-right">Current rate</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((product) => {
                      const price = currentRate.get(product.id);
                      return (
                        <tr key={product.id}>
                          <td>
                            <div className="flex items-center gap-2">
                              <span className="h-2.5 w-2.5 rounded-full" style={{ background: product.color }} />
                              <span className="font-medium text-ink-100">{product.name}</span>
                              <Badge tone="neutral">{product.code}</Badge>
                            </div>
                            <p className="mt-0.5 text-xs text-ink-500">Unit {product.unit}</p>
                          </td>
                          <td className="text-ink-400">{product.category}</td>
                          <td className="num text-right">
                            {price ? formatINR(price.ratePerLitre) : <span className="text-warn">Not priced</span>}
                          </td>
                          <td>
                            <StatusBadge status={product.isActive ? 'ACTIVE' : 'INACTIVE'} />
                          </td>
                          <td className="text-right">
                            <div className="flex items-center justify-end gap-1">
                              {can('prices.write') ? (
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => {
                                    setPriceFor(product);
                                    setPriceForm({
                                      rate: price?.ratePerLitre ?? '',
                                      effectiveFrom: '',
                                      note: '',
                                      scope: 'STATION',
                                    });
                                  }}
                                >
                                  <Tags className="h-3.5 w-3.5" /> Price
                                </Button>
                              ) : null}
                              {can('products.write') ? (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => {
                                    setEdit(product);
                                    setEditForm({
                                      name: product.name,
                                      category: product.category,
                                      color: product.color,
                                      isActive: Boolean(product.isActive),
                                    });
                                  }}
                                  aria-label={`Edit ${product.name}`}
                                >
                                  <Pencil className="h-3.5 w-3.5" />
                                </Button>
                              ) : null}
                              <DeleteRecordButton
                                path={`/catalog/products/${product.id}`}
                                label={product.name}
                                onDeleted={invalidate}
                              />
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {products.data ? (
                <Pagination
                  page={products.data.meta.page}
                  pageSize={products.data.meta.pageSize}
                  total={products.data.meta.total}
                  totalPages={products.data.meta.totalPages}
                  onPage={setPage}
                />
              ) : null}
            </>
          )}
        </Card>

        <Card className="xl:col-span-2">
          <CardHeader title="Price history" subtitle="Effective-dated rate changes; new prices never rewrite prior sales." />
          {prices.isLoading ? (
            <LoadingBlock />
          ) : (prices.data ?? []).length === 0 ? (
            <EmptyState title="No price records" description="Record the first price for the selected station." />
          ) : (
            <div className="max-h-[540px] overflow-y-auto overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Product</th>
                    <th className="text-right">Rate</th>
                    <th>Effective from</th>
                    <th>Scope</th>
                  </tr>
                </thead>
                <tbody>
                  {(prices.data ?? []).slice(0, 40).map((price) => (
                    <tr key={price.id}>
                      <td>
                        <p className="text-ink-200">{price.productName}</p>
                        <p className="text-xs text-ink-500">{price.productCode}</p>
                      </td>
                      <td className="num text-right text-ink-100">{formatINR(price.ratePerLitre)}</td>
                      <td className="text-xs text-ink-400">
                        {formatDateTime(price.effectiveFrom)}
                        {price.effectiveTo ? <span className="block text-ink-500">until {formatDateTime(price.effectiveTo)}</span> : <span className="block text-ok">current</span>}
                      </td>
                      <td className="text-xs text-ink-400">{price.stationName ?? 'All stations'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="New fuel product"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>
              Cancel
            </Button>
            <Button loading={create.isPending} onClick={() => create.mutate()}>
              Create product
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Code" required hint="e.g. MS, HSD, XP95">
            <Input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          </Field>
          <Field label="Name" required>
            <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <Field label="Category">
            <Select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
              <option value="PETROL">Petrol</option>
              <option value="DIESEL">Diesel</option>
              <option value="CNG">CNG</option>
              <option value="LUBRICANT">Lubricant</option>
            </Select>
          </Field>
          <Field label="Unit">
            <Input value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} />
          </Field>
          <Field label="Chart colour">
            <Input type="color" value={form.color} onChange={(e) => setForm({ ...form, color: e.target.value })} className="h-9 p-1" />
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(edit)}
        onClose={() => setEdit(null)}
        title={`Edit ${edit?.name ?? 'product'}`}
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
          <Field label="Category">
            <Select value={editForm.category} onChange={(e) => setEditForm({ ...editForm, category: e.target.value })}>
              <option value="PETROL">Petrol</option>
              <option value="DIESEL">Diesel</option>
              <option value="CNG">CNG</option>
              <option value="LUBRICANT">Lubricant</option>
            </Select>
          </Field>
          <Field label="Chart colour">
            <Input type="color" value={editForm.color} onChange={(e) => setEditForm({ ...editForm, color: e.target.value })} className="h-9 p-1" />
          </Field>
          <Field label="Status" className="sm:col-span-2">
            <Select value={editForm.isActive ? 'ACTIVE' : 'INACTIVE'} onChange={(e) => setEditForm({ ...editForm, isActive: e.target.value === 'ACTIVE' })}>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </Select>
          </Field>
        </div>
      </Modal>

      <Modal
        open={Boolean(priceFor)}
        onClose={() => setPriceFor(null)}
        title={`New price — ${priceFor?.name ?? ''}`}
        description="Prices are effective-dated. Existing sales keep the rate that applied when they were recorded."
        footer={
          <>
            <Button variant="ghost" onClick={() => setPriceFor(null)}>
              Cancel
            </Button>
            <Button loading={addPrice.isPending} onClick={() => addPrice.mutate()}>
              Save price
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Rate per litre (₹)" required>
            <Input
              inputMode="decimal"
              value={priceForm.rate}
              onChange={(e) => setPriceForm({ ...priceForm, rate: e.target.value })}
              placeholder="106.31"
            />
          </Field>
          <Field label="Effective from" hint="Leave blank to apply immediately (Asia/Kolkata).">
            <Input
              type="datetime-local"
              value={priceForm.effectiveFrom}
              onChange={(e) => setPriceForm({ ...priceForm, effectiveFrom: e.target.value })}
            />
          </Field>
          <Field label="Applies to">
            <Select value={priceForm.scope} onChange={(e) => setPriceForm({ ...priceForm, scope: e.target.value })}>
              <option value="STATION">This station</option>
              <option value="TENANT">All stations</option>
            </Select>
          </Field>
          <Field label="Note">
            <Input value={priceForm.note} onChange={(e) => setPriceForm({ ...priceForm, note: e.target.value })} />
          </Field>
        </div>
      </Modal>
    </div>
  );
}

function PackageIcon() {
  return <Fuel className="h-4 w-4" />;
}
