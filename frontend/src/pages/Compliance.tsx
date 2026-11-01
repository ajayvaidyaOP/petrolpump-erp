import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, FileBadge2, Pencil, Plus, ShieldAlert, ShieldCheck, Trash2 } from 'lucide-react';
import { COMPLIANCE_DOC_TYPES } from '@petroflow/shared';
import { apiDelete, apiGet, apiList, apiPatch, apiPost, type ListMeta } from '../lib/api';
import { ExportCsvButton } from '../components/OwnerActions';
import { useAuth, useStations } from '../lib/auth';
import { formatDate } from '../lib/format';
import {
  Badge, Button, Card, CardBody, CardHeader, ConfirmDialog, EmptyState, ErrorState, Field,
  Input, LoadingBlock, Modal, PageHeader, Select, StatCard, useToast,
} from '../components/ui';

interface ComplianceDoc {
  id: string;
  docType: string;
  title: string;
  docNumber: string | null;
  authority: string | null;
  issuedOn: string | null;
  expiresOn: string;
  renewalLeadDays: number;
  dispenserId: string | null;
  tankId: string | null;
  dispenserCode: string | null;
  tankCode: string | null;
  notes: string | null;
  daysLeft: number;
  status: 'OK' | 'EXPIRING' | 'EXPIRED';
}

interface ComplianceResponse {
  documents: ComplianceDoc[];
  summary: { expired: number; expiring: number; ok: number };
}

const DOC_LABELS: Record<string, string> = {
  PESO_LICENCE: 'PESO Licence',
  WM_STAMPING: 'W&M Stamping (Legal Metrology)',
  FIRE_NOC: 'Fire NOC',
  TRADE_LICENCE: 'Trade Licence',
  TANK_TESTING: 'Tank Testing',
  POLLUTION_NOC: 'Pollution NOC',
  ELECTRICAL_SAFETY: 'Electrical Safety',
  INSURANCE: 'Insurance',
  OTHER: 'Other',
};

const emptyForm = {
  docType: 'PESO_LICENCE', title: '', docNumber: '', authority: '', issuedOn: '', expiresOn: '',
  renewalLeadDays: '30', dispenserId: '', tankId: '', notes: '',
};

export default function Compliance() {
  const { can } = useAuth();
  const { stationId, station } = useStations();
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ComplianceDoc | null>(null);
  const [form, setForm] = useState({ ...emptyForm });
  const [deleteId, setDeleteId] = useState<string | null>(null);

  const docs = useQuery<ComplianceResponse>({
    queryKey: ['compliance', stationId],
    queryFn: () => apiGet<ComplianceResponse>(`/compliance?stationId=${stationId}`),
    enabled: Boolean(stationId),
  });
  const dispensers = useQuery<{ data: { id: string; code: string; name: string }[]; meta: ListMeta }>({
    queryKey: ['catalog-dispensers', stationId],
    queryFn: () => apiList(`/catalog/dispensers?stationId=${stationId}&pageSize=100`),
    enabled: formOpen && Boolean(stationId),
  });
  const tanks = useQuery<{ data: { id: string; code: string; name: string }[]; meta: ListMeta }>({
    queryKey: ['catalog-tanks', stationId],
    queryFn: () => apiList(`/catalog/tanks?stationId=${stationId}&pageSize=100`),
    enabled: formOpen && Boolean(stationId),
  });

  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['compliance'] });

  const save = useMutation({
    mutationFn: () => {
      const body = {
        docType: form.docType, title: form.title, docNumber: form.docNumber || undefined,
        authority: form.authority || undefined, issuedOn: form.issuedOn || undefined,
        expiresOn: form.expiresOn, renewalLeadDays: Number(form.renewalLeadDays) || 30,
        dispenserId: form.dispenserId || null, tankId: form.tankId || null, notes: form.notes || undefined,
      };
      return editing
        ? apiPatch(`/compliance/${editing.id}`, body)
        : apiPost('/compliance', { ...body, stationId });
    },
    onSuccess: () => {
      toast(editing ? 'Document updated' : 'Document added');
      setFormOpen(false); setEditing(null); setForm({ ...emptyForm });
      invalidate();
    },
    onError: (error: Error) => toast('Could not save document', { tone: 'danger', description: error.message }),
  });

  const remove = useMutation({
    mutationFn: () => apiDelete(`/compliance/${deleteId}`),
    onSuccess: () => { toast('Document deleted'); setDeleteId(null); invalidate(); },
    onError: (error: Error) => toast('Delete failed', { tone: 'danger', description: error.message }),
  });

  if (!can('compliance.read')) {
    return (
      <>
        <PageHeader title="Licences & statutory compliance" />
        <Card><EmptyState title="Compliance access required" description="Ask your organization owner for compliance access." icon={<ShieldCheck className="h-4 w-4" />} /></Card>
      </>
    );
  }

  const data = docs.data;
  const openCreate = () => { setEditing(null); setForm({ ...emptyForm }); setFormOpen(true); };
  const openEdit = (doc: ComplianceDoc) => {
    setEditing(doc);
    setForm({
      docType: doc.docType, title: doc.title, docNumber: doc.docNumber ?? '', authority: doc.authority ?? '',
      issuedOn: doc.issuedOn ?? '', expiresOn: doc.expiresOn, renewalLeadDays: String(doc.renewalLeadDays ?? 30),
      dispenserId: doc.dispenserId ?? '', tankId: doc.tankId ?? '', notes: doc.notes ?? '',
    });
    setFormOpen(true);
  };

  return (
    <>
      <PageHeader
        title="Licences & statutory compliance"
        description={`${station?.name ?? 'Station'} · PESO storage licence, Legal Metrology nozzle stamping, Fire NOC & pollution renewals`}
        actions={
          <>
            <ExportCsvButton path={`/compliance?stationId=${stationId}`} arrayKey="documents" filename="petroflow-compliance" />
            {can('compliance.write') ? (
              <Button variant="primary" size="sm" icon={<Plus className="h-4 w-4" />} onClick={openCreate}>Add document</Button>
            ) : null}
          </>
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="Expired — renew immediately" value={data?.summary.expired ?? '—'} icon={<ShieldAlert className="h-4 w-4" />} tone={(data?.summary.expired ?? 0) > 0 ? 'danger' : 'default'} />
        <StatCard label="Expiring soon" value={data?.summary.expiring ?? '—'} icon={<CalendarClock className="h-4 w-4" />} tone={(data?.summary.expiring ?? 0) > 0 ? 'warning' : 'default'} />
        <StatCard label="Active & valid" value={data?.summary.ok ?? '—'} icon={<ShieldCheck className="h-4 w-4" />} tone="success" />
      </div>

      <Card className="mt-4">
        <CardHeader title="Compliance register" subtitle="Ordered by nearest expiry date; upcoming renewals trigger automatic dashboard alerts." />
        {docs.isLoading ? (
          <LoadingBlock rows={4} />
        ) : docs.isError ? (
          <div className="p-4"><ErrorState error={docs.error} onRetry={() => void docs.refetch()} /></div>
        ) : !data || data.documents.length === 0 ? (
          <EmptyState
            title="No compliance documents registered"
            description="Track PESO storage licences, Legal Metrology dispenser stampings and Fire NOCs with proactive expiry alerts."
            icon={<FileBadge2 className="h-4 w-4" />}
            action={can('compliance.write') ? <Button size="sm" variant="primary" onClick={openCreate}>Add first document</Button> : undefined}
          />
        ) : (
          <CardBody className="grid gap-3.5 md:grid-cols-2 xl:grid-cols-3">
            {data.documents.map((doc) => (
              <div key={doc.id} className="min-w-0 break-words rounded-xl border border-carbon-700/80 bg-carbon-900/55 p-4">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate font-display text-sm font-bold text-ink-100">{doc.title}</p>
                    <p className="mt-0.5 text-[11px] text-ink-500">
                      {DOC_LABELS[doc.docType] ?? doc.docType}
                      {doc.dispenserCode ? ` · Dispenser ${doc.dispenserCode}` : ''}
                      {doc.tankCode ? ` · Tank ${doc.tankCode}` : ''}
                    </p>
                  </div>
                  {doc.status === 'EXPIRED' && <Badge tone="danger" dot>Expired</Badge>}
                  {doc.status === 'EXPIRING' && <Badge tone="warning" dot>{doc.daysLeft}d left</Badge>}
                  {doc.status === 'OK' && <Badge tone="success" dot>Valid</Badge>}
                </div>
                <dl className="mt-3 space-y-1.5 text-xs text-ink-400">
                  <div className="flex justify-between"><dt>Document no.</dt><dd className="num font-medium text-ink-200">{doc.docNumber ?? '—'}</dd></div>
                  <div className="flex justify-between"><dt>Issuing authority</dt><dd className="text-ink-200">{doc.authority ?? '—'}</dd></div>
                  <div className="flex justify-between"><dt>Issued on</dt><dd className="num">{doc.issuedOn ? formatDate(doc.issuedOn) : '—'}</dd></div>
                  <div className="flex justify-between"><dt>Expires on</dt><dd className={`num ${doc.status === 'EXPIRED' ? 'font-semibold text-danger' : 'font-medium text-ink-200'}`}>{formatDate(doc.expiresOn)}</dd></div>
                </dl>
                {can('compliance.write') && (
                  <div className="mt-3.5 flex gap-1.5 border-t border-carbon-700/70 pt-3">
                    <Button size="sm" variant="ghost" icon={<Pencil className="h-3.5 w-3.5" />} onClick={() => openEdit(doc)}>Renew / Edit</Button>
                    <Button size="sm" variant="ghost" aria-label="Delete document" onClick={() => setDeleteId(doc.id)}>
                      <Trash2 className="h-3.5 w-3.5 text-danger" />
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </CardBody>
        )}
      </Card>

      <Modal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title={editing ? 'Renew / edit document' : 'Add compliance document'}
        description="Upon renewal, update the expiry date and certificate number — prior state remains in the audit log."
        footer={
          <>
            <Button variant="ghost" onClick={() => setFormOpen(false)}>Cancel</Button>
            <Button variant="primary" loading={save.isPending} disabled={!form.title || !form.expiresOn} onClick={() => save.mutate()}>
              {editing ? 'Save changes' : 'Add document'}
            </Button>
          </>
        }
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Document type" required>
            <Select value={form.docType} onChange={(e) => setForm({ ...form, docType: e.target.value })}>
              {COMPLIANCE_DOC_TYPES.map((type) => <option key={type} value={type}>{DOC_LABELS[type]}</option>)}
            </Select>
          </Field>
          <Field label="Title" required>
            <Input placeholder="e.g. PESO Storage Licence" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </Field>
          <Field label="Document number">
            <Input value={form.docNumber} onChange={(e) => setForm({ ...form, docNumber: e.target.value })} />
          </Field>
          <Field label="Authority">
            <Input placeholder="e.g. Legal Metrology Pune" value={form.authority} onChange={(e) => setForm({ ...form, authority: e.target.value })} />
          </Field>
          <Field label="Issued on">
            <Input type="date" value={form.issuedOn} onChange={(e) => setForm({ ...form, issuedOn: e.target.value })} />
          </Field>
          <Field label="Expires on" required>
            <Input type="date" value={form.expiresOn} onChange={(e) => setForm({ ...form, expiresOn: e.target.value })} />
          </Field>
          <Field label="Dispenser (for W&M stamping)">
            <Select value={form.dispenserId} onChange={(e) => setForm({ ...form, dispenserId: e.target.value })}>
              <option value="">Station-wide</option>
              {(dispensers.data?.data ?? []).map((d) => <option key={d.id} value={d.id}>{d.code} · {d.name}</option>)}
            </Select>
          </Field>
          <Field label="Tank (for hydro / calibration test)">
            <Select value={form.tankId} onChange={(e) => setForm({ ...form, tankId: e.target.value })}>
              <option value="">Station-wide</option>
              {(tanks.data?.data ?? []).map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
            </Select>
          </Field>
          <Field label="Renewal reminder lead days">
            <Input inputMode="numeric" value={form.renewalLeadDays} onChange={(e) => setForm({ ...form, renewalLeadDays: e.target.value })} />
          </Field>
          <Field label="Notes">
            <Input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          </Field>
        </div>
      </Modal>

      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={() => remove.mutate()}
        title="Delete compliance document?"
        message="This removes the document from active tracking. For renewals, edit the expiry date instead of deleting."
        confirmLabel="Delete"
        danger
        loading={remove.isPending}
      />
    </>
  );
}
