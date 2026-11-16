import { useState } from 'react';
import { policyApi, claimsApi } from '../../api/services.js';
import { useApi } from '../../lib/hooks.js';
import { money, date } from '../../lib/format.js';
import { DOC_TYPES, TREATMENT_TYPES } from '../../lib/status.js';
import { uploadClaimDocument, fileSize } from '../../lib/upload.js';
import { useToast } from '../../components/Toast.jsx';
import Card from '../../components/Card.jsx';
import Button, { ButtonLink } from '../../components/Button.jsx';
import FileDrop from '../../components/FileDrop.jsx';
import Badge from '../../components/Badge.jsx';
import AsyncState from '../../components/AsyncState.jsx';
import s from './FileClaimPage.module.css';

const STEPS = ['Patient & hospital', 'Documents', 'Review & submit'];
const MULTI = new Set(['PRESCRIPTION', 'LAB_REPORT']);
const today = () => new Date().toISOString().slice(0, 10);

function validateDetails(d) {
  const e = {};
  if (!d.memberId) e.memberId = 'Choose who was treated.';
  if (!d.hospital.trim()) e.hospital = 'Enter the hospital name.';
  if (!d.admissionDate) e.admissionDate = 'Enter the admission date.';
  else if (d.admissionDate > today()) e.admissionDate = 'Admission date cannot be in the future.';
  if (!d.dischargeDate) e.dischargeDate = 'Enter the discharge date.';
  else if (d.admissionDate && d.dischargeDate < d.admissionDate) e.dischargeDate = 'Discharge must be on or after admission.';
  else if (d.dischargeDate > today()) e.dischargeDate = 'Discharge date cannot be in the future.';
  if (!d.diagnosis.trim()) e.diagnosis = 'Enter the diagnosis.';
  if (!d.treatmentType) e.treatmentType = 'Choose a treatment type.';
  const amt = Number(d.claimedAmount);
  if (!d.claimedAmount) e.claimedAmount = 'Enter the amount you are claiming.';
  else if (!Number.isFinite(amt) || amt <= 0) e.claimedAmount = 'Enter an amount greater than zero.';
  return e;
}

export default function FileClaimPage() {
  const policy = useApi(() => policyApi.mine(), []);
  return (
    <div>
      <div className="page-header">
        <div>
          <h1>File a claim</h1>
          <p>Takes about five minutes. Keep your hospital documents ready.</p>
        </div>
      </div>
      <AsyncState {...policy} onRetry={policy.reload}>
        {(p) => <Wizard policy={p} />}
      </AsyncState>
    </div>
  );
}

function Wizard({ policy }) {
  const toast = useToast();
  const [step, setStep] = useState(0);
  const [details, setDetails] = useState({
    memberId: policy.members[0]?.id || '', hospital: '', admissionDate: '', dischargeDate: '',
    diagnosis: '', treatmentType: '', claimedAmount: '',
  });
  const [errors, setErrors] = useState({});
  const [files, setFiles] = useState({}); // docType -> File[]
  const [docsError, setDocsError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [progress, setProgress] = useState({}); // `${type}:${i}` -> pct
  const [result, setResult] = useState(null);
  const [submitError, setSubmitError] = useState(null);

  const update = (k, v) => {
    setDetails((d) => ({ ...d, [k]: v }));
    setErrors((er) => (er[k] ? { ...er, [k]: undefined } : er)); // clear a field's error as soon as it is edited
  };
  const set = (k) => (e) => update(k, e.target.value);
  const member = policy.members.find((m) => m.id === details.memberId);
  const missingRequired = DOC_TYPES.filter((t) => t.required && !(files[t.value]?.length));

  const next = () => {
    if (step === 0) {
      const e = validateDetails(details);
      setErrors(e);
      if (Object.keys(e).length) {
        document.getElementById(`f-${Object.keys(e)[0]}`)?.focus();
        return;
      }
    }
    if (step === 1) {
      if (missingRequired.length) { setDocsError(`Add: ${missingRequired.map((t) => t.label).join(', ')}.`); return; }
      setDocsError(null);
    }
    setStep((x) => x + 1);
    window.scrollTo({ top: 0 });
  };

  const addFiles = (type, list) => setFiles((f) => ({ ...f, [type]: MULTI.has(type) ? [...(f[type] || []), ...list] : list.slice(0, 1) }));
  const removeFile = (type, idx) => setFiles((f) => ({ ...f, [type]: f[type].filter((_, i) => i !== idx) }));

  const submit = async () => {
    setSubmitting(true);
    setSubmitError(null);
    let claim = result?.claim;
    try {
      if (!claim) {
        claim = await claimsApi.create({
          policyNumber: policy.policyNumber,
          patient: { memberId: member.id, name: member.name, relation: member.relation },
          hospital: details.hospital.trim(),
          admissionDate: details.admissionDate,
          dischargeDate: details.dischargeDate,
          diagnosis: details.diagnosis.trim(),
          treatmentType: details.treatmentType,
          claimedAmount: Number(details.claimedAmount),
        });
        setResult({ claim, uploaded: false });
      }
      for (const [type, list] of Object.entries(files)) {
        for (let i = 0; i < list.length; i++) {
          const key = `${type}:${i}`;
          if (progress[key] === 100) continue; // already uploaded on an earlier attempt
          await uploadClaimDocument(claim.id, type, list[i], (pct) => setProgress((p) => ({ ...p, [key]: pct })));
          setProgress((p) => ({ ...p, [key]: 100 }));
        }
      }
      setResult({ claim, uploaded: true });
      toast.success(`Claim ${claim.id} submitted.`);
    } catch (err) {
      setSubmitError(claim
        ? `Your claim ${claim.id} was created, but a document failed to upload: ${err.message} Try again to upload the rest.`
        : err.message);
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  if (result?.uploaded) {
    return (
      <Card>
        <div className={s.done}>
          <div className={s.doneIcon} aria-hidden="true">✓</div>
          <h2>Claim submitted</h2>
          <p className="secondary">Your claim ID is</p>
          <p className={s.claimId}>{result.claim.id}</p>
          <p className="secondary small" style={{ maxWidth: 440 }}>We’re checking your documents now. You’ll see each step on the claim’s status page as it happens.</p>
          <div className="row" style={{ justifyContent: 'center', marginTop: 16 }}>
            <ButtonLink to={`/portal/claims/${result.claim.id}`}>Track this claim</ButtonLink>
            <ButtonLink to="/portal" variant="secondary">Back to overview</ButtonLink>
          </div>
        </div>
      </Card>
    );
  }

  return (
    <div className="stack">
      <ol className={s.stepper} aria-label="Progress">
        {STEPS.map((label, i) => (
          <li key={label} className={`${s.step} ${i === step ? s.current : ''} ${i < step ? s.complete : ''}`} aria-current={i === step ? 'step' : undefined}>
            <span className={s.stepNum} aria-hidden="true">{i < step ? '✓' : i + 1}</span>
            <span className={s.stepLabel}>{label}</span>
            <span className="sr-only">{i < step ? '(done)' : i === step ? '(current step)' : ''}</span>
          </li>
        ))}
      </ol>

      {step === 0 && (
        <Card title="Who was treated, and where?">
          <div className="stack">
            <Field id="memberId" label="Patient" error={errors.memberId}>
              <select id="f-memberId" className="select" value={details.memberId} onChange={set('memberId')} aria-invalid={Boolean(errors.memberId)}>
                {policy.members.map((m) => <option key={m.id} value={m.id}>{m.name} ({m.relation})</option>)}
              </select>
            </Field>
            <Field id="hospital" label="Hospital name" error={errors.hospital}>
              <input id="f-hospital" className="input" value={details.hospital} onChange={set('hospital')} placeholder="e.g. Manipal Hospital, Bengaluru" aria-invalid={Boolean(errors.hospital)} />
            </Field>
            <div className="grid-2">
              <Field id="admissionDate" label="Admission date" error={errors.admissionDate}>
                <input id="f-admissionDate" type="date" className="input" max={today()} value={details.admissionDate} onChange={set('admissionDate')} aria-invalid={Boolean(errors.admissionDate)} />
              </Field>
              <Field id="dischargeDate" label="Discharge date" error={errors.dischargeDate}>
                <input id="f-dischargeDate" type="date" className="input" max={today()} min={details.admissionDate || undefined} value={details.dischargeDate} onChange={set('dischargeDate')} aria-invalid={Boolean(errors.dischargeDate)} />
              </Field>
            </div>
            <Field id="diagnosis" label="Diagnosis" error={errors.diagnosis} hint="As written on the discharge summary.">
              <input id="f-diagnosis" className="input" value={details.diagnosis} onChange={set('diagnosis')} aria-invalid={Boolean(errors.diagnosis)} />
            </Field>
            <div className="grid-2">
              <Field id="treatmentType" label="Treatment type" error={errors.treatmentType}>
                <select id="f-treatmentType" className="select" value={details.treatmentType} onChange={set('treatmentType')} aria-invalid={Boolean(errors.treatmentType)}>
                  <option value="">Choose…</option>
                  {TREATMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </Field>
              <Field id="claimedAmount" label="Claimed amount (₹)" error={errors.claimedAmount} hint={details.claimedAmount && !errors.claimedAmount ? money(details.claimedAmount) : 'Total from the final hospital bill.'}>
                <input id="f-claimedAmount" className="input num" inputMode="numeric" value={details.claimedAmount} onChange={(e) => update('claimedAmount', e.target.value.replace(/[^\d]/g, ''))} placeholder="0" aria-invalid={Boolean(errors.claimedAmount)} />
              </Field>
            </div>
          </div>
        </Card>
      )}

      {step === 1 && (
        <Card title="Upload your documents">
          <div className="stack">
            <ul className={s.checklist} aria-label="Required documents">
              {DOC_TYPES.map((t) => {
                const have = files[t.value]?.length > 0;
                return (
                  <li key={t.value} className={have ? s.have : ''}>
                    <span aria-hidden="true">{have ? '✓' : t.required ? '○' : '–'}</span>
                    {t.label}{t.required ? '' : ' (optional)'}
                    <span className="sr-only">{have ? ' added' : t.required ? ' required, not added' : ' optional'}</span>
                  </li>
                );
              })}
            </ul>
            {docsError && <div className="notice danger" role="alert">{docsError}</div>}
            {DOC_TYPES.map((t) => (
              <div key={t.value} className={s.docRow}>
                <div className="row-between">
                  <span className="strong">{t.label}</span>
                  {t.required ? <Badge tone="info">Required</Badge> : <Badge tone="neutral">Optional</Badge>}
                </div>
                {(files[t.value] || []).map((f, i) => (
                  <div key={`${f.name}-${i}`} className={s.fileItem}>
                    <span aria-hidden="true">📄</span>
                    <span className={s.fileName}>{f.name}</span>
                    <span className="xsmall muted">{fileSize(f.size)}</span>
                    <Button variant="ghost" size="sm" onClick={() => removeFile(t.value, i)} aria-label={`Remove ${f.name}`}>Remove</Button>
                  </div>
                ))}
                {(MULTI.has(t.value) || !files[t.value]?.length) && (
                  <FileDrop compact multiple={MULTI.has(t.value)} label={`Drop ${t.label.toLowerCase()} here`} onFiles={(list) => addFiles(t.value, list)} />
                )}
              </div>
            ))}
          </div>
        </Card>
      )}

      {step === 2 && (
        <Card title="Check everything before you submit">
          <div className="stack">
            <dl className="dl">
              <dt>Policy</dt><dd className="mono">{policy.policyNumber}</dd>
              <dt>Patient</dt><dd>{member?.name} ({member?.relation})</dd>
              <dt>Hospital</dt><dd>{details.hospital}</dd>
              <dt>Stay</dt><dd>{date(details.admissionDate)} – {date(details.dischargeDate)}</dd>
              <dt>Diagnosis</dt><dd>{details.diagnosis}</dd>
              <dt>Treatment</dt><dd>{details.treatmentType}</dd>
              <dt>Claimed amount</dt><dd className="num">{money(details.claimedAmount)}</dd>
            </dl>
            <div>
              <h4 style={{ marginBottom: 8 }}>Documents</h4>
              <ul className="stack-sm" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
                {Object.entries(files).flatMap(([type, list]) => list.map((f, i) => {
                  const pct = progress[`${type}:${i}`];
                  return (
                    <li key={`${type}-${i}`} className={s.reviewFile}>
                      <div className="row-between">
                        <span className="small"><span className="strong">{DOC_TYPES.find((d) => d.value === type)?.label}</span> · {f.name}</span>
                        {pct != null && <span className="xsmall muted num">{pct === 100 ? '✓ Uploaded' : `${pct}%`}</span>}
                      </div>
                      {pct != null && (
                        <div className={s.progress} role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={`Uploading ${f.name}`}>
                          <div style={{ width: `${pct}%` }} />
                        </div>
                      )}
                    </li>
                  );
                }))}
              </ul>
            </div>
            <p className="small secondary">By submitting you confirm the information is accurate. Your insurer may contact the hospital to verify it.</p>
            {submitError && <div className="notice danger" role="alert">{submitError}</div>}
          </div>
        </Card>
      )}

      <div className={s.actions}>
        {step > 0 && !result?.claim && <Button variant="secondary" onClick={() => setStep((x) => x - 1)} disabled={submitting}>Back</Button>}
        <span style={{ flex: 1 }} />
        {step < 2 && <Button onClick={next}>Continue</Button>}
        {step === 2 && <Button onClick={submit} loading={submitting}>{submitting ? 'Submitting…' : result?.claim ? 'Retry upload' : 'Submit claim'}</Button>}
      </div>
    </div>
  );
}

function Field({ id, label, error, hint, children }) {
  return (
    <div className="field">
      <label htmlFor={`f-${id}`}>{label}</label>
      {children}
      {error ? <span className="error" role="alert">{error}</span> : hint ? <span className="hint">{hint}</span> : null}
    </div>
  );
}
