import { expect, test, type Page } from '@playwright/test';

/**
 * Regression guard for the app-wide render crash.
 *
 * The original failure: `crypto.randomUUID()` was called during render (idempotency
 * keys + toast ids). On a non-secure (plain HTTP) deployment `crypto.randomUUID` is
 * undefined, so the call threw and the single AppErrorBoundary replaced the whole
 * app — "This screen could not be displayed" — and no button worked. Sandboxed
 * preview iframes additionally throw SecurityError on localStorage access.
 *
 * This spec reproduces BOTH conditions and mocks the API so it needs no backend.
 */

const PERMS = [
  'stations.read', 'stations.write', 'products.read', 'products.write', 'prices.write',
  'tanks.read', 'tanks.write', 'dispensers.read', 'dispensers.write', 'employees.read', 'employees.write',
  'devices.read', 'devices.write', 'readings.read', 'readings.manual', 'shifts.read', 'shifts.open',
  'shifts.close', 'shifts.approve', 'sales.read', 'sales.write', 'stock.read', 'stock.write',
  'deliveries.read', 'deliveries.write', 'customers.read', 'customers.write', 'suppliers.read',
  'suppliers.write', 'payments.read', 'payments.write', 'expenses.read', 'expenses.write',
  'reconciliation.read', 'reconciliation.write', 'reports.read', 'density.read', 'density.write',
  'dayclose.manage', 'compliance.read', 'compliance.write', 'lubricants.read', 'lubricants.write',
  'attendance.read', 'attendance.write', 'audit.read', 'settings.read', 'settings.write', 'billing.read',
];

const stations = [
  { id: 'stn_ban', name: 'Demo Baner', code: 'DEMO-BAN', status: 'ACTIVE' },
  { id: 'stn_hin', name: 'Demo Hinjewadi', code: 'DEMO-HIN', status: 'ACTIVE' },
];

const me = {
  user: { id: 'usr_owner', name: 'Demo Owner', email: 'owner@demo.test', roleKey: 'OWNER', roleName: 'Owner', tenantId: 'tnt_demo', tenantName: 'Demo Fuels' },
  tenant: { id: 'tnt_demo', name: 'Demo Fuels', slug: 'demo', status: 'ACTIVE', isDemo: 1 },
  subscription: { status: 'ACTIVE', planName: 'Pro', planCode: 'PRO', trialEndsAt: null, endsAt: null, maxStations: 5, maxUsers: 20 },
  stations, permissions: PERMS, roleScope: 'ALL_STATIONS', isSuperAdmin: false, demoMode: true,
};

const meta = (n: number) => ({ page: 1, pageSize: 200, total: n, totalPages: 1 });
const product = (i: number) => ({ id: `prod_${i}`, code: `P${i}`, name: i === 1 ? 'Petrol' : 'Diesel', category: i === 1 ? 'PETROL' : 'DIESEL', unit: 'L', color: '#067647', isActive: 1, createdAt: '2026-01-01T00:00:00Z' });
const party = (i: number, kind: string) => ({ id: `${kind}_${i}`, name: `${kind === 'cust' ? 'Customer' : 'Supplier'} ${i}`, phone: '9000000000', email: `${kind}${i}@demo.test`, gstin: '', address: 'Pune', creditLimit: '100000', creditDays: 15, status: 'ACTIVE', isActive: 1, balance: '0', outstanding: '0', createdAt: '2026-01-01T00:00:00Z' });

async function mockApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname.replace('/api/v1', '');
    const json = (data: unknown, m?: unknown) => route.fulfill({ json: m ? { data, meta: m } : { data } });
    if (req.method() === 'DELETE') return json({ deleted: true });
    if (['POST', 'PATCH', 'PUT'].includes(req.method())) return json({ id: 'new_1' });
    if (p === '/auth/me') return json(me);
    if (p.startsWith('/catalog/stations')) return json(stations);
    if (p.startsWith('/catalog/products')) return json([product(1), product(2)], meta(2));
    if (p.startsWith('/catalog/prices')) return json([]);
    if (p.startsWith('/catalog/customers')) return json([party(1, 'cust'), party(2, 'cust')], meta(2));
    if (p.startsWith('/catalog/suppliers')) return json([party(1, 'supp'), party(2, 'supp')], meta(2));
    if (p.startsWith('/shifts/alerts')) return json({ alerts: [], unclosed: [] });
    return json([], meta(0));
  });
}

test.beforeEach(async ({ page }) => {
  // Non-secure context: crypto.randomUUID is unavailable.
  await page.addInitScript(() => {
    try { Object.defineProperty(window.crypto, 'randomUUID', { value: undefined, configurable: true }); } catch { /* noop */ }
    const block = () => { throw new DOMException('Storage is disabled in this context', 'SecurityError'); };
    try { Object.defineProperty(window, 'localStorage', { get: block, configurable: true }); } catch { /* noop */ }
    try { Object.defineProperty(window, 'sessionStorage', { get: block, configurable: true }); } catch { /* noop */ }
  });
  await mockApi(page);
});

test('app boots without the global error boundary when crypto.randomUUID and storage are unavailable', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  for (const path of ['/products', '/stations', '/customers', '/suppliers', '/shifts', '/deliveries']) {
    await page.goto(path);
    await expect(page.getByText('This screen could not be displayed')).toHaveCount(0);
    // The navigation shell must survive so the user can always move between screens.
    await expect(page.locator('nav, aside').first()).toBeVisible();
  }
  expect(errors).toEqual([]);
});

test('owner sees delete controls that open a confirmation, and export controls that download', async ({ page }) => {
  await page.goto('/products');
  const deleteButtons = page.locator('button[aria-label^="Delete"]');
  await expect(deleteButtons.first()).toBeVisible();
  await deleteButtons.first().click();
  await expect(page.getByRole('button', { name: 'Delete', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /Export CSV/ }).first().click(),
  ]);
  expect(download.suggestedFilename()).toBe('petroflow-products.csv');
});
