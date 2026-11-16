import { DOC_TYPES, docTypeLabel } from '../../../lib/status.js';
import { dateTime } from '../../../lib/format.js';
import { fileSize } from '../../../lib/upload.js';
import Card from '../../../components/Card.jsx';
import Table from '../../../components/Table.jsx';
import Badge from '../../../components/Badge.jsx';
import EmptyState from '../../../components/EmptyState.jsx';
import { DOC_STATUS } from '../../policyholder/ClaimDetailPage.jsx';

export default function DocumentsTab({ claim }) {
  const missing = DOC_TYPES.filter((t) => t.required && !claim.documents.some((d) => d.docType === t.value && d.status === 'VERIFIED'));
  return (
    <div className="stack">
      {missing.length > 0 && (
        <div className="notice warning">Missing verified documents: {missing.map((m) => m.label).join(', ')}.</div>
      )}
      <Card padded={false} title="Documents">
        {claim.documents.length === 0 ? <EmptyState title="No documents uploaded yet" /> : (
          <Table
            rows={claim.documents}
            caption="Claim documents"
            columns={[
              { key: 'docType', header: 'Type', render: (d) => <span className="strong">{docTypeLabel(d.docType)}</span> },
              { key: 'fileName', header: 'File', render: (d) => <span>{d.fileName} <span className="xsmall muted">{fileSize(d.size)}</span></span> },
              { key: 'uploadedAt', header: 'Uploaded', render: (d) => dateTime(d.uploadedAt) },
              {
                key: 'status', header: 'Verification',
                render: (d) => {
                  const st = DOC_STATUS[d.status] || DOC_STATUS.PENDING;
                  return (
                    <div>
                      <Badge tone={st.tone} glyph={st.glyph}>{st.label}</Badge>
                      {d.reason && <div className="xsmall" style={{ color: 'var(--danger-fg)', marginTop: 4 }}>{d.reason}</div>}
                    </div>
                  );
                },
              },
              {
                key: 'preview', header: 'Preview',
                render: (d) => (d.previewUrl
                  ? <a href={d.previewUrl} target="_blank" rel="noreferrer">Open ↗</a>
                  : <span className="xsmall muted" title="The backend did not return a preview link">No preview</span>),
              },
            ]}
          />
        )}
      </Card>
    </div>
  );
}
