import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
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

const edge: Record<ToastMessage['tone'], string> = {
  info: 'border-l-stone-500',
  success: 'border-l-pine-600',
  warning: 'border-l-amber-500',
  error: 'border-l-red-600',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const dismiss = useCallback((id: number) => setToasts((current) => current.filter((t) => t.id !== id)), []);
  const show = useCallback((toast: Omit<ToastMessage, 'id'>) => {
    const id = Date.now() + Math.random();
    setToasts((current) => [...current.slice(-2), { ...toast, id }]);
    window.setTimeout(() => dismiss(id), 8000);
  }, [dismiss]);
  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4 sm:items-end">
        {toasts.map((toast) => (
          <div key={toast.id}
               className={cx('pointer-events-auto w-full max-w-sm rounded border border-stone-300 border-l-4 bg-white px-4 py-3 shadow-md', edge[toast.tone])}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="font-semibold text-stone-900">{toast.title}</p>
                {toast.message && <p className="mt-0.5 text-sm text-stone-600">{toast.message}</p>}
                {toast.href && (
                  <Link to={toast.href} onClick={() => dismiss(toast.id)} className="mt-1 inline-block text-sm text-pine-700 underline">
                    Open
                  </Link>
                )}
              </div>
              <button type="button" onClick={() => dismiss(toast.id)} className="text-lg leading-none text-stone-400 hover:text-stone-700"
                      aria-label="Dismiss">×</button>
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
