import { useState } from 'react';
import Modal from '../../../components/Modal.jsx';
import Button from '../../../components/Button.jsx';
import CommentField, { commentValid } from '../../../components/CommentField.jsx';

/**
 * A modal for any adjuster change. The confirm button stays disabled until the
 * fields are valid and a justification of at least 10 characters is entered.
 */
export default function CommentModal({ open, title, confirmLabel, onClose, onSubmit, children, valid = true }) {
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const close = () => { if (busy) return; setComment(''); setError(null); onClose(); };
  const submit = async () => {
    setBusy(true); setError(null);
    try {
      await onSubmit(comment.trim());
      setComment('');
      onClose();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      onClose={close}
      footer={(
        <>
          <Button variant="secondary" onClick={close} disabled={busy}>Cancel</Button>
          <Button onClick={submit} disabled={!valid || !commentValid(comment)} loading={busy}>{confirmLabel}</Button>
        </>
      )}
    >
      <div className="stack">
        {children}
        <CommentField value={comment} onChange={setComment} />
        {error && <div className="notice danger" role="alert">{error}</div>}
      </div>
    </Modal>
  );
}
