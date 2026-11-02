import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { errorMessage } from '../api/client';
import { useLogin, useMe } from '../api/queries';
import { Button, Field, Input, Notice } from '../components/ui';

/** Only same-site relative paths are accepted as a post-login redirect (no open redirects). */
export function safeNext(value: string | null): string {
  return value && value.startsWith('/') && !value.startsWith('//') ? value : '/dashboard';
}

const demoAccounts = [
  ['asha@example.com', 'Visitor'],
  ['staff@citycare.example', 'Clinic staff'],
  ['admin@citycare.example', 'Clinic admin'],
  ['staff@northbridge.example', 'College staff'],
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
    <div className="mx-auto max-w-sm">
      <h1 className="text-2xl font-semibold text-stone-900">Log in</h1>
      <p className="mt-1 text-sm text-stone-600">
        No account yet? <Link to="/register" className="text-pine-700 underline underline-offset-2">Sign up</Link>
      </p>
      <form onSubmit={submit} className="mt-6 space-y-4 rounded-md border border-stone-200 bg-white p-5" noValidate>
        {login.isError && <Notice tone="error">{errorMessage(login.error)}</Notice>}
        <Field label="Email" htmlFor="email">
          <Input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password" htmlFor="password">
          <Input id="password" type="password" autoComplete="current-password" required value={password}
                 onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Button type="submit" className="w-full" loading={login.isPending} disabled={!email || !password}>Log in</Button>
      </form>

      <div className="mt-6 text-sm">
        <p className="font-medium text-stone-700">Demo logins</p>
        <table className="mt-2 w-full">
          <tbody>
            {demoAccounts.map(([address, role]) => (
              <tr key={address} className="border-b border-stone-200 last:border-0">
                <td className="py-1.5">
                  <button type="button" className="text-pine-700 underline-offset-2 hover:underline" onClick={() => setEmail(address)}>
                    {address}
                  </button>
                </td>
                <td className="py-1.5 text-right text-stone-500">{role}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="mt-2 text-xs text-stone-500">
          {import.meta.env.DEV
            ? 'Local password: Password123!'
            : 'The demo password is shared with you separately.'}
        </p>
      </div>
    </div>
  );
}
