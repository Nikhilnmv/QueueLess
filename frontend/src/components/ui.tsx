import { useEffect, useId, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode,
  type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { errorMessage } from '../api/client';

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ');
}

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'plain';
type ButtonSize = 'sm' | 'md' | 'lg';

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-pine-700 text-white hover:bg-pine-800 disabled:bg-pine-300',
  secondary: 'border border-stone-300 bg-white text-stone-800 hover:bg-stone-50 disabled:text-stone-400',
  danger: 'border border-red-300 bg-white text-red-700 hover:bg-red-50 disabled:text-red-300',
  plain: 'text-pine-700 underline-offset-2 hover:underline disabled:text-stone-400',
};

const sizes: Record<ButtonSize, string> = {
  sm: 'px-2.5 py-1 text-sm',
  md: 'px-3.5 py-2 text-sm',
  lg: 'px-5 py-2.5 text-base',
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
}

/** Disabled while its request runs, which also stops double submissions. */
export function Button({ variant = 'primary', size = 'md', loading = false, className, children, disabled,
                         type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cx('inline-flex items-center justify-center gap-2 rounded font-medium transition-colors disabled:cursor-not-allowed',
        variant !== 'plain' && sizes[size], variants[variant], className)}
      {...rest}
    >
      {loading && <span className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" aria-hidden />}
      {children}
    </button>
  );
}

/** A white box with an optional title row. */
export function Panel({ title, actions, children, className }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string;
}) {
  return (
    <section className={cx('rounded-md border border-stone-200 bg-white', className)}>
      {(title || actions) && (
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-stone-200 px-4 py-2.5">
          {title && <h2 className="text-sm font-semibold text-stone-800">{title}</h2>}
          {actions}
        </div>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function PageTitle({ title, description, actions, back }: {
  title: ReactNode; description?: ReactNode; actions?: ReactNode; back?: ReactNode;
}) {
  return (
    <div className="mb-6">
      {back && <div className="mb-2 text-sm">{back}</div>}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-stone-900">{title}</h1>
          {description && <p className="mt-1 text-stone-600">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return <p role="status" className="py-8 text-center text-stone-500">{label}…</p>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="rounded border border-dashed border-stone-300 px-4 py-6 text-center text-sm text-stone-600">{children}</p>;
}

type NoticeTone = 'info' | 'success' | 'warning' | 'error';
const noticeTones: Record<NoticeTone, string> = {
  info: 'border-stone-400 bg-white text-stone-800',
  success: 'border-pine-600 bg-pine-50 text-pine-900',
  warning: 'border-amber-500 bg-amber-50 text-amber-900',
  error: 'border-red-600 bg-red-50 text-red-900',
};

export function Notice({ tone = 'info', title, children }: { tone?: NoticeTone; title?: string; children?: ReactNode }) {
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={cx('rounded-r border-l-4 px-4 py-3 text-sm', noticeTones[tone])}>
      {title && <p className="font-semibold">{title}</p>}
      {children && <div className={title ? 'mt-0.5' : undefined}>{children}</div>}
    </div>
  );
}

export function ErrorNotice({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Notice tone="error" title="Something went wrong">
      <p>{errorMessage(error)}</p>
      {onRetry && <button type="button" className="mt-1 font-medium underline" onClick={onRetry}>Try again</button>}
    </Notice>
  );
}

export function Field({ label, error, hint, children, htmlFor }: {
  label: string; error?: string; hint?: string; children: ReactNode; htmlFor: string;
}) {
  return (
    <div className="space-y-1">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-stone-700">{label}</label>
      {children}
      {hint && !error && <p className="text-xs text-stone-500">{hint}</p>}
      {error && <p className="text-xs text-red-700" id={`${htmlFor}-error`}>{error}</p>}
    </div>
  );
}

const inputClass = 'block w-full rounded border border-stone-300 bg-white px-3 py-2 text-stone-900 placeholder:text-stone-400 '
  + 'focus:border-pine-600 focus:outline-none focus:ring-1 focus:ring-pine-600 disabled:bg-stone-100 sm:text-sm';

export function Input({ invalid, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }) {
  return <input aria-invalid={invalid || undefined} className={cx(inputClass, invalid && 'border-red-500', className)} {...rest} />;
}

export function Select({ className, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={cx(inputClass, 'pr-8', className)} {...rest}>{children}</select>;
}

export function TextArea({ className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cx(inputClass, className)} {...rest} />;
}

/** Native <dialog>: focus handling and Escape-to-close come from the browser. */
export function Dialog({ open, onClose, title, children, footer }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  return (
    <dialog ref={ref} aria-labelledby={titleId} onClose={onClose}
            className="m-auto w-[min(32rem,calc(100%-2rem))] rounded-md border border-stone-300 p-0 shadow-lg backdrop:bg-stone-900/40">
      {open && (
        <div className="p-5">
          <h2 id={titleId} className="text-lg font-semibold text-stone-900">{title}</h2>
          <div className="mt-3 text-sm text-stone-700">{children}</div>
          {footer && <div className="mt-5 flex flex-wrap justify-end gap-2">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}

export function ConfirmDialog({ open, title, children, confirmLabel, loading, onConfirm, onCancel }: {
  open: boolean; title: string; children: ReactNode; confirmLabel: string; loading?: boolean;
  onConfirm: () => void; onCancel: () => void;
}) {
  return (
    <Dialog open={open} onClose={onCancel} title={title} footer={
      <>
        <Button variant="secondary" onClick={onCancel} disabled={loading}>Go back</Button>
        <Button variant="danger" onClick={onConfirm} loading={loading}>{confirmLabel}</Button>
      </>
    }>
      {children}
    </Dialog>
  );
}

/** Two-column list of labels and values. */
export function Details({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[minmax(8rem,auto)_1fr] gap-x-6 gap-y-2 text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-stone-500">{label}</dt>
          <dd className="text-stone-900">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

// Shared table classes so every list looks the same.
export const table = 'w-full border-collapse text-left text-sm';
export const th = 'border-b border-stone-200 bg-stone-50 px-3 py-2 font-medium text-stone-600';
export const td = 'border-b border-stone-100 px-3 py-2 align-middle';
