import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronDown, KeyRound, Pencil, Plus, ShieldCheck, UserRound } from 'lucide-react';
import { PERMISSION_GROUPS, STAFF_PRESETS, type Permission } from '@petroflow/shared';
import { apiGet, apiPatch, apiPost } from '../lib/api';
import { useAuth, useStations } from '../lib/auth';
import { initials } from '../lib/format';
import { Badge, Button, Card, ErrorState, Field, Input, LoadingBlock, Modal, PageHeader, useToast } from '../components/ui';
import { ExportCsvButton } from '../components/OwnerActions';
import { AttendancePanel } from '../components/AttendancePanel';

interface Employee {
  id: string;
  name: string;
  email: string;
  phone: string | null;
  roleKey: string;
  status: string;
  stationIds: string[];
  permissions: string[];
}

const roleTitle: Record<string, string> = {
  OWNER: 'Owner',
  MANAGER: 'Manager',
  STAFF: 'Staff',
};

export default function Employees() {
  const { me, can, refresh } = useAuth();
  const { stations } = useStations();
  const { toast } = useToast();
  const client = useQueryClient();
  const [edit, setEdit] = useState<Employee | null>(null);
  const [create, setCreate] = useState(false);
  const [form, setForm] = useState(emptyForm(stations.map((station) => station.id)));
  const [resetFor, setResetFor] = useState<Employee | null>(null);
  const [newPassword, setNewPassword] = useState('');
  const employees = useQuery<Employee[]>({ queryKey: ['employees'], queryFn: () => apiGet<Employee[]>('/catalog/employees'), enabled: can('employees.read') });
  const rows = employees.data ?? [];
  const owner = rows.find((row) => row.roleKey === 'OWNER' && row.status === 'ACTIVE');
  const manager = rows.find((row) => row.roleKey === 'MANAGER');
  const staff = rows.filter((row) => row.roleKey === 'STAFF');
  const invalidate = () => {
    void client.invalidateQueries({ queryKey: ['employees'] });
    void client.invalidateQueries({ queryKey: ['attendance'] });
    refresh();
  };
  const openCreate = () => {
    setEdit(null);
    setCreate(true);
    setForm(emptyForm(stations.map((station) => station.id), STAFF_PRESETS[0].permissions));
  };
  const openEdit = (employee: Employee) => {
    setCreate(false);
    setEdit(employee);
    setForm({
      name: employee.name,
      phone: employee.phone ?? '',
      email: employee.email,
      password: '',
      stationIds: employee.stationIds,
      permissions: employee.permissions,
    });
  };
  const save = useMutation({
    mutationFn: () => create
      ? apiPost('/catalog/employees', {
          name: form.name,
          email: form.email,
          phone: form.phone || null,
          password: form.password,
          roleKey: 'STAFF',
          stationIds: form.stationIds,
          permissions: form.permissions,
        })
      : apiPatch(`/catalog/employees/${edit?.id}`, {
          name: form.name,
          phone: form.phone || null,
          ...(edit?.roleKey !== 'OWNER' ? { stationIds: form.stationIds, status: 'ACTIVE' } : {}),
          ...(edit?.roleKey === 'STAFF' ? { permissions: form.permissions } : {}),
        }),
    onSuccess: () => {
      toast(create ? 'Staff login created' : 'Account updated', { description: create ? 'They can sign in with the email and password you set.' : undefined });
      setEdit(null);
      setCreate(false);
      invalidate();
    },
    onError: (error: Error) => toast('Could not save account', { tone: 'danger', description: error.message }),
  });
  const disable = useMutation({
    mutationFn: (employee: Employee) => apiPatch(`/catalog/employees/${employee.id}`, { status: 'DISABLED' }),
    onSuccess: () => { toast('Login disabled', { description: 'Their sessions were signed out.' }); invalidate(); },
    onError: (error: Error) => toast('Could not disable login', { tone: 'danger', description: error.message }),
  });
  const reset = useMutation({
    mutationFn: () => apiPost(`/catalog/employees/${resetFor?.id}/reset-password`, { newPassword }),
    onSuccess: () => { toast('Password reset', { description: 'Other sessions were signed out.' }); setResetFor(null); setNewPassword(''); },
    onError: (error: Error) => toast('Could not reset password', { tone: 'danger', description: error.message }),
  });

  return (
    <>
      <PageHeader
        title="Your team"
        description="One owner. Add staff logins and tick exactly what each person can open."
        actions={<><ExportCsvButton path="/catalog/employees" filename="petroflow-staff" />{can('employees.write') ? <Button variant="primary" onClick={openCreate} icon={<Plus className="h-4 w-4" />}>Add staff login</Button> : null}</>}
      />
      {can('employees.read') && (employees.isLoading ? <LoadingBlock rows={6} /> : employees.isError ? <ErrorState error={employees.error} onRetry={() => void employees.refetch()} /> : (
        <>
          <div className="grid gap-4 lg:grid-cols-2">
            {[owner, manager].filter((row): row is Employee => Boolean(row)).map((employee) => (
              <PersonCard
                key={employee.id}
                employee={employee}
                stations={stations}
                you={employee.id === me?.user.id}
                onEdit={() => openEdit(employee)}
                onReset={() => setResetFor(employee)}
              />
            ))}
          </div>

          <div className="mt-6">
            <div className="mb-3 flex items-end justify-between gap-3">
              <div>
                <p className="font-display text-lg font-bold tracking-tight text-ink-100">Staff logins</p>
                <p className="text-xs text-ink-500">Each person sees only the screens you allow. The server checks this on every request, not just in the menu.</p>
              </div>
              <Badge tone="neutral">{staff.filter((row) => row.status === 'ACTIVE').length} active</Badge>
            </div>
            {staff.length === 0 ? (
              <Card className="p-6">
                <p className="text-sm font-semibold text-ink-200">No staff logins yet</p>
                <p className="mt-1 max-w-xl text-xs leading-relaxed text-ink-500">Add a cashier, attendant or accountant. Start from a preset, then untick anything they should not touch. Creating logins stays with the owner.</p>
              </Card>
            ) : (
              <div className="grid gap-3">
                {staff.map((employee) => (
                  <StaffRow
                    key={employee.id}
                    employee={employee}
                    stations={stations}
                    you={employee.id === me?.user.id}
                    canWrite={can('employees.write')}
                    onEdit={() => openEdit(employee)}
                    onReset={() => setResetFor(employee)}
                    onDisable={() => disable.mutate(employee)}
                  />
                ))}
              </div>
            )}
          </div>

          <details className="card mt-6 group">
            <summary className="flex min-h-14 cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 text-sm font-semibold text-ink-200">
              Attendance
              <ChevronDown className="h-4 w-4 transition group-open:rotate-180" />
            </summary>
            <div className="border-t border-carbon-700/60 p-4"><AttendancePanel canWrite={can('attendance.write')} /></div>
          </details>
        </>
      ))}

      {can('attendance.read') && !can('employees.read') && (
        <details className="card group" open>
          <summary className="flex min-h-14 cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 text-sm font-semibold text-ink-200">
            Attendance
            <ChevronDown className="h-4 w-4 transition group-open:rotate-180" />
          </summary>
          <div className="border-t border-carbon-700/60 p-4"><AttendancePanel canWrite={can('attendance.write')} /></div>
        </details>
      )}

      <Modal
        open={create || Boolean(edit)}
        onClose={() => { setEdit(null); setCreate(false); }}
        title={create ? 'Add staff login' : edit?.roleKey === 'OWNER' ? 'Edit owner profile' : edit?.roleKey === 'MANAGER' ? 'Edit manager access' : 'Edit staff access'}
        description={create || edit?.roleKey === 'STAFF' ? 'Untick a permission and that screen disappears for them. They cannot grant themselves more.' : 'The owner and manager seats stay fixed. Use a staff login for a custom set.'}
        size="lg"
        footer={(
          <>
            <Button variant="ghost" onClick={() => { setEdit(null); setCreate(false); }}>Cancel</Button>
            <Button
              variant="primary"
              loading={save.isPending}
              disabled={!form.name.trim() || (create && (!form.email || form.password.length < 10 || form.permissions.length === 0 || form.stationIds.length === 0)) || (!create && edit?.roleKey !== 'OWNER' && form.stationIds.length === 0) || (!create && edit?.roleKey === 'STAFF' && form.permissions.length === 0)}
              onClick={() => save.mutate()}
            >
              {create ? 'Create login' : 'Save changes'}
            </Button>
          </>
        )}
      >
        <AccessForm form={form} setForm={setForm} create={create} showPermissions={create || edit?.roleKey === 'STAFF'} showStations={create || edit?.roleKey !== 'OWNER'} stations={stations} />
      </Modal>

      <Modal
        open={Boolean(resetFor)}
        onClose={() => setResetFor(null)}
        title={`Reset password · ${resetFor?.name ?? ''}`}
        description="All existing sessions for this account will be signed out."
        size="sm"
        footer={(
          <>
            <Button variant="ghost" onClick={() => setResetFor(null)}>Cancel</Button>
            <Button variant="primary" loading={reset.isPending} disabled={newPassword.length < 10} onClick={() => reset.mutate()}>Reset password</Button>
          </>
        )}
      >
        <Field label="New password" required hint="At least 10 characters, with a letter and a number.">
          <Input type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
        </Field>
      </Modal>
    </>
  );
}

function emptyForm(stationIds: string[], permissions: readonly string[] = []) {
  return { name: '', phone: '', email: '', password: '', stationIds: [...stationIds], permissions: [...permissions] };
}

function PersonCard({ employee, stations, you, onEdit, onReset }: {
  employee: Employee;
  stations: { id: string; name: string }[];
  you: boolean;
  onEdit: () => void;
  onReset: () => void;
}) {
  const pumpNames = employee.roleKey === 'OWNER'
    ? 'All pumps'
    : stations.filter((station) => employee.stationIds.includes(station.id)).map((station) => station.name).join(' · ') || 'No pumps assigned';
  return (
    <Card className="relative overflow-hidden">
      <span aria-hidden className="brand-gradient absolute inset-x-0 top-0 h-[2.5px] opacity-90" />
      <div className="flex items-start justify-between gap-3 p-5">
        <div className="flex items-start gap-3">
          <span className="brand-gradient grid h-12 w-12 shrink-0 place-items-center rounded-2xl font-display text-sm font-extrabold text-onbrand">{initials(employee.name)}</span>
          <div>
            <p className="font-display text-lg font-extrabold tracking-tight text-ink-100">
              {employee.name}
              {you && <span className="ml-2 rounded-full border border-accent/25 bg-accent/10 px-2 py-0.5 align-middle font-sans text-[10px] font-bold uppercase tracking-wider text-accent">You</span>}
            </p>
            <p className="font-mono text-xs text-ink-400">{employee.email}</p>
            <p className="mt-1 text-xs text-ink-500">{pumpNames}</p>
          </div>
        </div>
        <Badge tone={employee.roleKey === 'OWNER' ? 'accent' : 'neutral'} dot>{roleTitle[employee.roleKey] ?? employee.roleKey}</Badge>
      </div>
      <div className="flex flex-wrap gap-2 border-t border-carbon-700/60 px-5 py-3">
        <Button size="sm" variant="outline" onClick={onEdit} icon={<Pencil className="h-3.5 w-3.5" />}>
          {employee.status !== 'ACTIVE' ? 'Re-enable' : employee.roleKey === 'MANAGER' ? 'Edit access' : 'Edit'}
        </Button>
        <Button size="sm" variant="ghost" onClick={onReset} icon={<KeyRound className="h-3.5 w-3.5" />}>Reset password</Button>
      </div>
    </Card>
  );
}

function StaffRow({ employee, stations, you, canWrite, onEdit, onReset, onDisable }: {
  employee: Employee;
  stations: { id: string; name: string }[];
  you: boolean;
  canWrite: boolean;
  onEdit: () => void;
  onReset: () => void;
  onDisable: () => void;
}) {
  const labels = permissionLabels(employee.permissions);
  const pumps = stations.filter((station) => employee.stationIds.includes(station.id)).map((station) => station.name).join(' · ') || 'No pumps';
  return (
    <Card className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-carbon-700 bg-carbon-800 text-ink-300"><UserRound className="h-4 w-4" /></span>
          <div className="min-w-0">
            <p className="font-semibold text-ink-100">
              {employee.name}
              {you && <span className="ml-2 text-[10px] font-bold uppercase tracking-wider text-accent">You</span>}
              {employee.status !== 'ACTIVE' && <Badge tone="warning" className="ml-2">Disabled</Badge>}
            </p>
            <p className="truncate font-mono text-xs text-ink-400">{employee.email}</p>
            <p className="mt-1 text-xs text-ink-500">{pumps}</p>
          </div>
        </div>
        {canWrite && (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" onClick={onEdit} icon={<Pencil className="h-3.5 w-3.5" />}>{employee.status === 'ACTIVE' ? 'Edit permissions' : 'Re-enable'}</Button>
            <Button size="sm" variant="ghost" onClick={onReset} icon={<KeyRound className="h-3.5 w-3.5" />}>Password</Button>
            {employee.status === 'ACTIVE' && !you && <Button size="sm" variant="ghost" onClick={onDisable}>Disable</Button>}
          </div>
        )}
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {labels.length === 0 ? <span className="text-xs text-ink-500">No permissions</span> : labels.map((label) => (
          <span key={label} className="rounded-md border border-carbon-700/80 bg-carbon-800/70 px-2 py-1 text-[11px] text-ink-300">{label}</span>
        ))}
      </div>
    </Card>
  );
}

function AccessForm({ form, setForm, create, showPermissions, showStations, stations }: {
  form: ReturnType<typeof emptyForm>;
  setForm: (value: ReturnType<typeof emptyForm>) => void;
  create: boolean;
  showPermissions: boolean;
  showStations: boolean;
  stations: { id: string; name: string }[];
}) {
  const selected = useMemo(() => new Set(form.permissions), [form.permissions]);
  const togglePermission = (key: string) => {
    setForm({ ...form, permissions: selected.has(key) ? form.permissions.filter((item) => item !== key) : [...form.permissions, key] });
  };
  const toggleStation = (id: string) => {
    setForm({ ...form, stationIds: form.stationIds.includes(id) ? form.stationIds.filter((stationId) => stationId !== id) : [...form.stationIds, id] });
  };
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Full name" required><Input value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Field>
        <Field label="Phone"><Input type="tel" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} /></Field>
        {create && (
          <>
            <Field label="Email address" required><Input type="email" autoComplete="off" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} /></Field>
            <Field label="Temporary password" required hint="At least 10 characters, with a letter and a number.">
              <Input type="password" autoComplete="new-password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} />
            </Field>
          </>
        )}
      </div>
      {showStations && (
        <fieldset>
          <legend className="label">Assigned pumps</legend>
          <div className="grid gap-2 sm:grid-cols-2">
            {stations.map((station) => (
              <label key={station.id} className="flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border border-carbon-700/70 px-3 text-sm text-ink-200">
                <input type="checkbox" checked={form.stationIds.includes(station.id)} onChange={() => toggleStation(station.id)} className="h-4 w-4 accent-accent" />
                {station.name}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      {showPermissions && (
        <div>
          <div className="mb-3 flex flex-wrap gap-2">
            {STAFF_PRESETS.map((preset) => (
              <button
                key={preset.key}
                type="button"
                onClick={() => setForm({ ...form, permissions: [...preset.permissions] })}
                className="rounded-full border border-carbon-700 bg-carbon-900 px-3 py-1.5 text-left text-xs font-semibold text-ink-200 transition hover:border-accent/50"
              >
                {preset.label}
              </button>
            ))}
          </div>
          <p className="mb-3 text-xs text-ink-500">{STAFF_PRESETS.find((preset) => sameSet(preset.permissions, form.permissions))?.description ?? 'Custom set. Creating new logins cannot be given to staff.'}</p>
          <div className="grid gap-3">
            {PERMISSION_GROUPS.map((group) => (
              <fieldset key={group.id} className={`rounded-xl border p-3 ${group.sensitive ? 'border-warn/30 bg-warn/5' : 'border-carbon-700/70'}`}>
                <legend className="px-1 text-xs font-bold uppercase tracking-wider text-ink-400">{group.label}</legend>
                <p className="mb-2 text-[11px] text-ink-500">{group.hint}</p>
                <div className="grid gap-1.5 sm:grid-cols-2">
                  {group.permissions.map((permission) => (
                    <label key={permission.key} className="flex cursor-pointer items-start gap-2 rounded-lg px-1 py-1 text-xs text-ink-200">
                      <input type="checkbox" checked={selected.has(permission.key)} onChange={() => togglePermission(permission.key)} className="mt-0.5 h-4 w-4 accent-accent" />
                      <span>{permission.label}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
            ))}
          </div>
          <p className="mt-3 flex items-start gap-2 text-xs text-ink-500">
            <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-accent" />
            {form.permissions.length} permission{form.permissions.length === 1 ? '' : 's'} selected. Price changes and settings are in the sensitive group — leave them off unless you mean it.
          </p>
        </div>
      )}
    </div>
  );
}

function permissionLabels(keys: string[]) {
  const labels = new Map<string, string>();
  for (const group of PERMISSION_GROUPS) for (const permission of group.permissions) labels.set(permission.key, permission.label);
  return keys.map((key) => labels.get(key as Permission) ?? key);
}

function sameSet(left: readonly string[], right: readonly string[]) {
  if (left.length !== right.length) return false;
  const set = new Set(right);
  return left.every((item) => set.has(item));
}
