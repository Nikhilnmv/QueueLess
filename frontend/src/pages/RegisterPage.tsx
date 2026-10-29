import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { UserPlus } from 'lucide-react';
import { ApiError, errorMessage } from '../api/client';
import { useMe, useRegister } from '../api/queries';
import { Alert, Button, Card, Field, Input } from '../components/ui';
import { safeNext } from './LoginPage';

export function RegisterPage() {
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const me = useMe();
  const register = useRegister();
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirm: '' });
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  if (me.data) return <Navigate to={next} replace />;

  const serverErrors = register.error instanceof ApiError ? register.error.fieldErrors : {};
  const errors = { ...serverErrors, ...localErrors };
  const update = (field: keyof typeof form) => (value: string) => setForm((current) => ({ ...current, [field]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const problems: Record<string, string> = {};
    if (form.fullName.trim().length < 2) problems.fullName = 'Enter your full name';
    if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) problems.email = 'Enter a valid email address';
    if (form.password.length < 8) problems.password = 'Use at least 8 characters';
    if (form.password.length > 72) problems.password = 'Use at most 72 characters';
    if (form.confirm !== form.password) problems.confirm = 'Passwords do not match';
    setLocalErrors(problems);
    if (Object.keys(problems).length > 0) return;
    register.mutate({ fullName: form.fullName.trim(), email: form.email.trim(), password: form.password },
      { onSuccess: () => navigate(next, { replace: true }) });
  };

  const generalError = register.isError && Object.keys(serverErrors).length === 0 ? errorMessage(register.error) : null;

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold text-slate-900">Create your account</h1>
      <p className="mt-1 text-slate-600">
        Already registered? <Link to="/login" className="font-medium text-indigo-600 hover:underline">Log in</Link>.
      </p>
      <Card className="mt-6">
        <form onSubmit={submit} className="space-y-4" noValidate>
          {generalError && <Alert tone="error">{generalError}</Alert>}
          <Field label="Full name" htmlFor="fullName" error={errors.fullName}>
            <Input id="fullName" autoComplete="name" value={form.fullName} invalid={!!errors.fullName}
                   onChange={(e) => update('fullName')(e.target.value)} />
          </Field>
          <Field label="Email" htmlFor="email" error={errors.email}>
            <Input id="email" type="email" autoComplete="email" value={form.email} invalid={!!errors.email}
                   onChange={(e) => update('email')(e.target.value)} />
          </Field>
          <Field label="Password" htmlFor="password" error={errors.password} hint="8 to 72 characters">
            <Input id="password" type="password" autoComplete="new-password" value={form.password} invalid={!!errors.password}
                   onChange={(e) => update('password')(e.target.value)} />
          </Field>
          <Field label="Confirm password" htmlFor="confirm" error={errors.confirm}>
            <Input id="confirm" type="password" autoComplete="new-password" value={form.confirm} invalid={!!errors.confirm}
                   onChange={(e) => update('confirm')(e.target.value)} />
          </Field>
          <Button type="submit" className="w-full" loading={register.isPending} icon={<UserPlus className="size-4" aria-hidden />}>
            Create account
          </Button>
          <p className="text-xs text-slate-500">
            New accounts are customer accounts. Staff and administrator access is granted by an organization administrator.
          </p>
        </form>
      </Card>
    </div>
  );
}
