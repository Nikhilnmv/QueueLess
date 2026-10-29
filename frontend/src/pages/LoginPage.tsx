import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { LogIn } from 'lucide-react';
import { errorMessage } from '../api/client';
import { useLogin, useMe } from '../api/queries';
import { Alert, Button, Card, Field, Input } from '../components/ui';

/** Only same-site relative paths are accepted as a post-login redirect (no open redirects). */
export function safeNext(value: string | null): string {
  return value && value.startsWith('/') && !value.startsWith('//') ? value : '/dashboard';
}

const demoAccounts = [
  ['Customer', 'asha@example.com'],
  ['Clinic staff', 'staff@citycare.example'],
  ['Clinic admin', 'admin@citycare.example'],
  ['College staff', 'staff@northbridge.example'],
];

export function LoginPage() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const me = useMe();
  const login = useLogin();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  if (me.data) return <Navigate to={next} replace />;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    login.mutate({ email, password }, { onSuccess: () => navigate(next, { replace: true }) });
  };

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold text-slate-900">Log in</h1>
      <p className="mt-1 text-slate-600">Welcome back. New here? <Link to="/register" className="font-medium text-indigo-600 hover:underline">Create an account</Link>.</p>
      <Card className="mt-6">
        <form onSubmit={submit} className="space-y-4" noValidate>
          {login.isError && <Alert tone="error">{errorMessage(login.error)}</Alert>}
          <Field label="Email" htmlFor="email">
            <Input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </Field>
          <Field label="Password" htmlFor="password">
            <Input id="password" type="password" autoComplete="current-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <Button type="submit" className="w-full" loading={login.isPending} icon={<LogIn className="size-4" aria-hidden />}
                  disabled={!email || !password}>
            Log in
          </Button>
        </form>
      </Card>
      <details className="mt-6 rounded-lg bg-white p-4 text-sm text-slate-600 ring-1 ring-slate-200">
        <summary className="cursor-pointer font-medium text-slate-700">Demo accounts</summary>
        <ul className="mt-2 space-y-1">
          {demoAccounts.map(([role, address]) => (
            <li key={address}>
              <button type="button" className="text-indigo-600 hover:underline" onClick={() => setEmail(address)}>{address}</button>
              <span className="text-slate-500"> · {role}</span>
            </li>
          ))}
        </ul>
        <p className="mt-2 text-xs text-slate-500">
          {import.meta.env.DEV
            ? 'Local development password: Password123!'
            : 'The demo password is set by the deployment (QUEUELESS_SEED_PASSWORD) and shared with evaluators separately.'}
        </p>
      </details>
    </div>
  );
}
