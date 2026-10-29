import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { BellRing, X } from 'lucide-react';
import { cx } from './ui';

interface ToastMessage {
  id: number;
  title: string;
  message?: string;
  tone: 'info' | 'success' | 'warning' | 'error';
  href?: string;
}

interface ToastApi {
  show: (toast: Omit<ToastMessage, 'id'>) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const toneClass: Record<ToastMessage['tone'], string> = {
  info: 'ring-indigo-200',
  success: 'ring-emerald-300',
  warning: 'ring-amber-300',
  error: 'ring-rose-300',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const dismiss = useCallback((id: number) => setToasts((current) => current.filter((t) => t.id !== id)), []);
  const show = useCallback((toast: Omit<ToastMessage, 'id'>) => {
    const id = Date.now() + Math.random();
    setToasts((current) => [...current.slice(-3), { ...toast, id }]);
    window.setTimeout(() => dismiss(id), 8000);
  }, [dismiss]);
  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4 sm:items-end">
        {toasts.map((toast) => (
          <div key={toast.id} className={cx('pointer-events-auto w-full max-w-sm rounded-xl bg-white p-4 shadow-lg ring-1', toneClass[toast.tone])}>
            <div className="flex items-start gap-3">
              <BellRing className="mt-0.5 size-5 shrink-0 text-indigo-600" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="font-medium text-slate-900">{toast.title}</p>
                {toast.message && <p className="mt-0.5 text-sm text-slate-600">{toast.message}</p>}
                {toast.href && (
                  <Link to={toast.href} onClick={() => dismiss(toast.id)} className="mt-1 inline-block text-sm font-medium text-indigo-600 hover:underline">
                    View
                  </Link>
                )}
              </div>
              <button type="button" onClick={() => dismiss(toast.id)} className="text-slate-400 hover:text-slate-600" aria-label="Dismiss">
                <X className="size-4" />
              </button>
            </div>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside ToastProvider');
  return context;
}
