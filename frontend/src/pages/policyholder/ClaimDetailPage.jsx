import { useCallback, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { claimsApi, auditApi } from '../../api/services.js';
import { POLL_INTERVAL_MS } from '../../config/api.js';
import { useApi, useInterval } from '../../lib/hooks.js';
import { money, date, dateTime } from '../../lib/format.js';
import { FINAL_STATUSES, docTypeLabel } from '../../lib/status.js';
import { describeEvent, isPolicyholderEvent } from '../../lib/auditText.js';
import { uploadClaimDocument } from '../../lib/upload.js';
import { useToast } from '../../components/Toast.jsx';
import Card from '../../components/Card.jsx';
import Button from '../../components/Button.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import Badge from '../../components/Badge.jsx';
import Timeline from '../../components/Timeline.jsx';
import Modal from '../../components/Modal.jsx';
import FileDrop from '../../components/FileDrop.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import s from './ClaimDetailPage.module.css';

export const DOC_STATUS = {
  VERIFIED: { tone: 'success', glyph: '✓', label: 'Verified' },
  REJECTED: { tone: 'danger', glyph: '✕', label: 'Rejected' },
  PENDING: { tone: 'warning', glyph: '◔', label: 'Checking' },
  AWAITING_UPLOAD: { tone: 'neutral', glyph: '○', label: 'Uploading' },
};

export default function ClaimDetailPage() {
  const { id } = useParams();
  const data = useApi(async () => {
    const [claim, trail] = await Promise.all([claimsApi.byId(id), auditApi.trail(id)]);
    return { claim, trail };
  }, [id]);
  const lastSeen = useRef(null);

  const claim = data.data?.claim;
  const polling = Boolean(claim) && !FINAL_STATUSES.has(claim.status);

  const poll = useCallback(async () => {
    try {
      const st = await claimsApi.status(id);
      const key = `${st.status}|${st.updatedAt}`;
      const docsPending = claim?.documents.some((d) => d.status === 'PENDING' || d.status === 'AWAITING_UPLOAD');
      if (key !== lastSeen.current || docsPending) {
        lastSeen.current = key;
        data.reload({ silent: true });
      }
    } catch { /* keep showing the last good data */ }
  }, [id, claim, data]);

  useInterval(poll, POLL_INTERVAL_MS, polling);

  return (
    <div>
      <nav className="small" style={{ marginBottom: 12 }}><Link to="/portal/claims">← My claims</Link></nav>
      <AsyncState {...data} onRetry={data.reload}>
        {({ claim: c, trail }) => <Detail claim={c} trail={trail} polling={polling} onChanged={() => data.reload({ silent: true })} />}
      </AsyncState>
    </div>
  );
}

function Detail({ claim, trail, polling, onChanged }) {
  const [reupload, setReupload] = useState(null); // doc being replaced
  const events = trail.filter(isPolicyholderEvent).reverse();

  return (
    <div className="stack-lg">
      <div className="page-header" style={{ marginBottom: 0 }}>
        <div>
          <div className="row" style={{ gap: 12 }}>
            <h1 className="mono">{claim.id}</h1>
            <StatusBadge status={claim.status} />
          </div>
          <p>{claim.patient.name} ({claim.patient.relation}) · {claim.hospital}</p>
        </div>
        {polling && <span className={s.live} aria-live="polite"><span className={s.pulse} aria-hidden="true" />Updating live</span>}
      </div>

      <Outcome claim={claim} />

      <div className={s.layout}>
        <Card title="Progress">
          {events.length ? (
            <Timeline items={events.map((e) => {
              const d = describeEvent(e);
              return { id: e.id, title: d.title, description: d.description, tone: d.tone, time: dateTime(e.at) };
            })} />
          ) : <EmptyState title="No updates yet" />}
        </Card>

        <div className="stack">
          <Card title="Claim details">
            <dl className="dl">
              <dt>Policy</dt><dd className="mono">{claim.policyNumber}</dd>
              <dt>Stay</dt><dd>{date(claim.admissionDate)} – {date(claim.dischargeDate)}</dd>
              <dt>Diagnosis</dt><dd>{claim.diagnosis}</dd>
              <dt>Treatment</dt><dd>{claim.treatmentType}</dd>
              <dt>Claimed</dt><dd className="num">{money(claim.claimedAmount)}</dd>
              {claim.approvedAmount != null && (<><dt>Approved</dt><dd className="num">{money(claim.approvedAmount)}</dd></>)}
              {claim.paymentReference && (<><dt>Payment ref.</dt><dd className="mono">{claim.paymentReference}</dd></>)}
              <dt>Filed</dt><dd>{dateTime(claim.createdAt)}</dd>
            </dl>
          </Card>

          <Card title="Documents" padded={false}>
            {claim.documents.length === 0 ? (
              <EmptyState title="No documents uploaded" message="Upload your discharge summary, final bill and ID proof so we can check your claim." />
            ) : (
              <ul className={s.docs}>
                {claim.documents.map((d) => {
                  const st = DOC_STATUS[d.status] || DOC_STATUS.PENDING;
                  return (
                    <li key={d.id} className={s.doc}>
                      <div className="row-between">
                        <div style={{ minWidth: 0 }}>
                          <div className="strong small">{docTypeLabel(d.docType)}</div>
                          <div className={`xsmall muted ${s.fileName}`}>{d.fileName}</div>
                        </div>
                        <Badge tone={st.tone} glyph={st.glyph}>{st.label}</Badge>
                      </div>
                      {d.status === 'REJECTED' && (
                        <div className={s.rejected}>
                          <p className="small">{d.reason}</p>
                          <Button size="sm" variant="secondary" onClick={() => setReupload(d)}>Upload again</Button>
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </div>
      </div>

      <ReuploadModal claimId={claim.id} doc={reupload} onClose={() => setReupload(null)} onDone={() => { setReupload(null); onChanged(); }} />
    </div>
  );
}

function Outcome({ claim }) {
  if (claim.status === 'SETTLED') {
    return <div className="notice success" role="status"><strong>Paid.</strong> {money(claim.approvedAmount)} was sent to your registered bank account. Payment reference <span className="mono">{claim.paymentReference}</span>.</div>;
  }
  if (claim.status === 'REJECTED') {
    return <div className="notice danger" role="status"><strong>Your claim was not approved.</strong> Reason: {claim.rejectionReason}</div>;
  }
  if (claim.status === 'APPROVED' || claim.status === 'SETTLING') {
    return <div className="notice success" role="status"><strong>Approved for {money(claim.approvedAmount)}.</strong> The payment is being processed.</div>;
  }
  if (claim.status === 'SETTLEMENT_FAILED') {
    return <div className="notice warning" role="status"><strong>Approved for {money(claim.approvedAmount)}, but the payment didn’t go through.</strong> Our team is retrying it. You don’t need to do anything.</div>;
  }
  if (claim.documents.some((d) => d.status === 'REJECTED')) {
    return <div className="notice warning" role="status"><strong>Action needed:</strong> one of your documents couldn’t be accepted. Upload it again below.</div>;
  }
  return null;
}

function ReuploadModal({ claimId, doc, onClose, onDone }) {
  const toast = useToast();
  const [file, setFile] = useState(null);
  const [pct, setPct] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const close = () => { if (busy) return; setFile(null); setPct(null); setError(null); onClose(); };

  const upload = async () => {
    setBusy(true); setError(null);
    try {
      await uploadClaimDocument(claimId, doc.docType, file, setPct);
      toast.success(`${docTypeLabel(doc.docType)} uploaded. We’ll check it shortly.`);
      setFile(null); setPct(null);
      onDone();
    } catch (err) {
      setError(err.message);
      toast.error(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={Boolean(doc)}
      title={doc ? `Upload ${docTypeLabel(doc.docType).toLowerCase()} again` : ''}
      onClose={close}
      footer={(
        <>
          <Button variant="secondary" onClick={close} disabled={busy}>Cancel</Button>
          <Button onClick={upload} disabled={!file} loading={busy}>Upload</Button>
        </>
      )}
    >
      {doc && (
        <div className="stack">
          <div className="notice danger small">{doc.reason}</div>
          {file ? (
            <div className="stack-sm">
              <div className="row-between small"><span className="strong">{file.name}</span>{!busy && <Button variant="ghost" size="sm" onClick={() => setFile(null)}>Change</Button>}</div>
              {pct != null && (
                <div className={s.progress} role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label="Upload progress"><div style={{ width: `${pct}%` }} /></div>
              )}
            </div>
          ) : <FileDrop onFiles={(f) => setFile(f[0])} />}
          {error && <p className="small" style={{ color: 'var(--danger-fg)' }} role="alert">{error}</p>}
        </div>
      )}
    </Modal>
  );
}
