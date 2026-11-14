'use client';

import { useCallback, useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? '/api/v1';

type Profile = {
  user: { id: string; email: string };
  tenant: { id: string; name: string };
  role: 'ADMIN' | 'USER';
};

type Summary = {
  websites: number;
  activeWebsites: number;
  domains: number;
  recentActivity: Array<{
    id: string;
    action: string;
    createdAt: string;
    actor: { email: string } | null;
  }>;
};

type Website = {
  id: string;
  name: string;
  slug: string;
  hostname: string;
  status:
    | 'PENDING'
    | 'PROVISIONING'
    | 'STARTING'
    | 'STOPPING'
    | 'RESTARTING'
    | 'DELETING'
    | 'ACTIVE'
    | 'STOPPED'
    | 'FAILED';
  errorCode: string | null;
  createdAt: string;
  updatedAt: string;
};

class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

const navigation = [
  { label: 'Overview', icon: '⌂' },
  { label: 'Websites', icon: '▣' },
  { label: 'Domains', icon: '◎' },
  { label: 'Emails', icon: '✉' },
  { label: 'Databases', icon: '▤' },
  { label: 'Files', icon: '▱' },
];

async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase();
  const headers = new Headers(init?.headers);
  if (init?.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    let csrfToken = readCsrfCookie();
    if (!csrfToken) {
      const csrfResponse = await fetch(`${API_URL}/auth/csrf`, { credentials: 'include' });
      if (!csrfResponse.ok)
        throw new ApiError('Unable to initialize request security', csrfResponse.status);
      const csrfResult = (await csrfResponse.json()) as { csrfToken: string };
      csrfToken = csrfResult.csrfToken;
    }
    headers.set('X-CSRF-Token', csrfToken);
  }

  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    credentials: 'include',
    headers,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const message = Array.isArray(body?.message) ? body.message.join(', ') : body?.message;
    throw new ApiError(message ?? `Request failed (${response.status})`, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function readCsrfCookie(): string | null {
  const cookie = document.cookie.split('; ').find((entry) => entry.startsWith('anb_csrf='));
  return cookie?.slice('anb_csrf='.length) ?? null;
}

export default function HomePage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [websites, setWebsites] = useState<Website[]>([]);
  const [checking, setChecking] = useState(true);
  const [registering, setRegistering] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [darkMode, setDarkMode] = useState(false);
  const [activeNav, setActiveNav] = useState('Overview');
  const [websiteName, setWebsiteName] = useState('');
  const [websiteBusy, setWebsiteBusy] = useState('');

  const loadSummary = useCallback(async () => {
    const result = await apiRequest<Summary>('/dashboard/summary');
    setSummary(result);
  }, []);

  const loadWebsites = useCallback(async () => {
    const result = await apiRequest<Website[]>('/websites');
    setWebsites(result);
  }, []);

  const loadProfile = useCallback(async () => {
    try {
      const result = await apiRequest<Profile>('/auth/me');
      setProfile(result);
      setError('');
    } catch (requestError) {
      setProfile(null);
      if (!(requestError instanceof ApiError && requestError.status === 401)) {
        setError(requestError instanceof Error ? requestError.message : 'Unable to load account');
      }
      setChecking(false);
      return;
    }

    try {
      await Promise.all([loadSummary(), loadWebsites()]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Unable to load dashboard');
    } finally {
      setChecking(false);
    }
  }, [loadSummary, loadWebsites]);

  useEffect(() => {
    void loadProfile();
  }, [loadProfile]);

  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError('');
    const form = new FormData(event.currentTarget);
    const payload = {
      email: String(form.get('email') ?? ''),
      password: String(form.get('password') ?? ''),
      ...(registering ? { workspaceName: String(form.get('workspaceName') ?? '') } : {}),
    };

    try {
      await apiRequest(`/auth/${registering ? 'register' : 'login'}`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      await loadProfile();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Authentication failed');
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setError('');
    try {
      await apiRequest('/auth/logout', { method: 'POST' });
      setProfile(null);
      setSummary(null);
      setWebsites([]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Could not sign out');
    }
  }

  async function createWebsite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWebsiteBusy('create');
    setError('');
    try {
      await apiRequest('/websites', {
        method: 'POST',
        body: JSON.stringify({ name: websiteName }),
      });
      setWebsiteName('');
      await Promise.all([loadSummary(), loadWebsites()]);
    } catch (requestError) {
      setError(
        requestError instanceof Error ? requestError.message : 'Website provisioning failed',
      );
    } finally {
      setWebsiteBusy('');
    }
  }

  async function operateWebsite(website: Website, action: 'start' | 'stop' | 'restart' | 'delete') {
    if (
      action === 'delete' &&
      !window.confirm(`Permanently delete "${website.name}" and stop its website container?`)
    ) {
      return;
    }
    setWebsiteBusy(website.id);
    setError('');
    try {
      await apiRequest(`/websites/${website.id}${action === 'delete' ? '' : `/${action}`}`, {
        method: action === 'delete' ? 'DELETE' : 'POST',
      });
      await Promise.all([loadSummary(), loadWebsites()]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Website operation failed');
    } finally {
      setWebsiteBusy('');
    }
  }

  if (checking) {
    return (
      <main className="grid min-h-screen place-items-center text-sm text-muted">
        Loading your secure workspace…
      </main>
    );
  }

  if (!profile) {
    return (
      <main className="grid min-h-screen place-items-center px-4 py-10">
        <section className="auth-card w-full max-w-md rounded-3xl bg-white p-8 shadow-soft">
          <div className="mb-8 flex items-center gap-3">
            <BrandMark />
            <div>
              <p className="text-sm font-bold tracking-tight">ANB Hosting</p>
              <p className="text-xs text-muted">Your workspace, all in one place</p>
            </div>
          </div>
          <h1 className="text-2xl font-bold tracking-tight">
            {registering ? 'Create your workspace' : 'Welcome back'}
          </h1>
          <p className="mt-2 text-sm text-muted">
            {registering
              ? 'Start with a secure hosting workspace.'
              : 'Sign in to manage your hosting.'}
          </p>
          <form onSubmit={submitAuth} className="mt-7 space-y-4">
            {registering && (
              <label className="block text-sm font-medium">
                Workspace name
                <input
                  required
                  minLength={2}
                  maxLength={80}
                  name="workspaceName"
                  autoComplete="organization"
                  className="focus-ring mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface)] px-4 py-3 outline-none"
                  placeholder="Acme Studio"
                />
              </label>
            )}
            <label className="block text-sm font-medium">
              Email address
              <input
                required
                type="email"
                name="email"
                autoComplete="email"
                className="focus-ring mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface)] px-4 py-3 outline-none"
                placeholder="you@example.com"
              />
            </label>
            <label className="block text-sm font-medium">
              Password
              <input
                required
                type="password"
                name="password"
                minLength={12}
                maxLength={128}
                autoComplete={registering ? 'new-password' : 'current-password'}
                className="focus-ring mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface)] px-4 py-3 outline-none"
                placeholder="At least 12 characters"
              />
            </label>
            {error && (
              <p role="alert" className="text-sm text-red-600">
                {error}
              </p>
            )}
            <button
              disabled={busy}
              className="focus-ring w-full rounded-xl bg-brand px-4 py-3 font-semibold text-white transition hover:opacity-90 disabled:opacity-60"
            >
              {busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}
            </button>
          </form>
          <p className="mt-6 text-center text-sm text-muted">
            {registering ? 'Already have an account?' : 'New to ANB Hosting?'}{' '}
            <button
              className="focus-ring rounded font-semibold text-brand"
              onClick={() => {
                setRegistering(!registering);
                setError('');
              }}
            >
              {registering ? 'Sign in' : 'Create workspace'}
            </button>
          </p>
        </section>
      </main>
    );
  }

  return (
    <main className={darkMode ? 'dark min-h-screen' : 'min-h-screen'}>
      <div className="mx-auto flex min-h-screen max-w-[1600px]">
        <aside className="sidebar hidden w-64 shrink-0 border-r border-[var(--line)] bg-white px-5 py-6 lg:flex lg:flex-col">
          <div className="mb-10 flex items-center gap-3 px-2">
            <BrandMark />
            <div>
              <p className="text-sm font-bold">ANB Hosting</p>
              <p className="text-xs text-muted">Control panel</p>
            </div>
          </div>
          <p className="mb-3 px-3 text-[10px] font-bold uppercase tracking-[0.16em] text-muted">
            Workspace
          </p>
          <nav className="space-y-1">
            {navigation.map((item) => (
              <NavButton
                key={item.label}
                item={item}
                active={activeNav === item.label}
                onClick={() => setActiveNav(item.label)}
              />
            ))}
          </nav>
          <div className="mt-auto rounded-2xl bg-[#f3f2ff] p-4 dark:bg-[var(--surface-subtle)]">
            <p className="text-sm font-semibold">Need a hand?</p>
            <p className="mt-1 text-xs leading-5 text-muted">
              Your hosting toolkit is growing. More controls arrive in the next phase.
            </p>
            <span className="mt-3 inline-flex rounded-full bg-white px-2.5 py-1 text-[10px] font-bold text-brand dark:bg-[var(--surface)]">
              FOUNDATION PHASE
            </span>
          </div>
        </aside>

        <section className="min-w-0 flex-1">
          <header className="topbar sticky top-0 z-10 flex h-[72px] items-center justify-between border-b border-[var(--line)] bg-white/90 px-4 backdrop-blur md:px-8">
            <div className="flex items-center gap-3">
              <div className="lg:hidden">
                <BrandMark />
              </div>
              <div>
                <p className="text-sm font-semibold">{profile.tenant.name}</p>
                <p className="hidden text-xs text-muted sm:block">Hosting workspace</p>
              </div>
            </div>
            <div className="flex items-center gap-2 sm:gap-4">
              <button
                aria-label="Toggle dark theme"
                onClick={() => setDarkMode(!darkMode)}
                className="focus-ring rounded-xl border border-[var(--line)] px-3 py-2 text-sm"
              >
                {darkMode ? '☼' : '◐'}
              </button>
              <button
                onClick={signOut}
                className="focus-ring rounded-lg px-2 py-2 text-xs font-medium text-muted hover:text-ink"
              >
                Sign out
              </button>
              <div className="grid h-9 w-9 place-items-center rounded-full bg-[#ebe9ff] text-xs font-bold text-brand">
                {profile.user.email.slice(0, 1).toUpperCase()}
              </div>
            </div>
          </header>

          <div className="mx-auto max-w-6xl px-4 pb-28 pt-7 md:px-8 md:pt-10 lg:pb-10">
            <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
              <div>
                <p className="text-sm font-medium text-muted">Your hosting at a glance</p>
                <h1 className="mt-1 text-2xl font-bold tracking-tight md:text-3xl">
                  {activeNav === 'Overview' ? 'Dashboard overview' : activeNav}
                </h1>
              </div>
              <span className="rounded-xl border border-[var(--line)] bg-[var(--surface)] px-4 py-2.5 text-xs font-semibold text-muted">
                {activeNav === 'Websites' ? 'Static hosting engine' : 'Workspace overview'}
              </span>
            </div>

            {error && (
              <p
                role="alert"
                className="mb-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
              >
                {error}
              </p>
            )}

            {activeNav === 'Overview' && (
              <>
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricCard
                    title="Websites"
                    value={summary?.websites ?? 0}
                    note="In this workspace"
                    icon="▣"
                  />
                  <MetricCard
                    title="Active websites"
                    value={summary?.activeWebsites ?? 0}
                    note="Running sites"
                    icon="↗"
                    tone="green"
                  />
                  <MetricCard
                    title="Domains"
                    value={summary?.domains ?? 0}
                    note="Connected to workspace"
                    icon="◎"
                    tone="blue"
                  />
                  <MetricCard
                    title="Storage"
                    value="—"
                    note="Measured when file management is enabled"
                    icon="▱"
                    tone="amber"
                  />
                </div>

                <div className="mt-6 grid gap-5 xl:grid-cols-[1.55fr_1fr]">
                  <section className="dashboard-card rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft md:p-6">
                    <div className="flex items-center justify-between">
                      <div>
                        <h2 className="font-bold">Websites</h2>
                        <p className="mt-1 text-xs text-muted">Your provisioned static sites</p>
                      </div>
                      <button
                        onClick={() => setActiveNav('Websites')}
                        className="text-xs font-semibold text-brand"
                      >
                        Manage sites →
                      </button>
                    </div>
                    {websites.length ? (
                      <div className="mt-5 space-y-3">
                        {websites.slice(0, 3).map((website) => (
                          <div key={website.id} className="flex items-center justify-between gap-3">
                            <div className="min-w-0">
                              <p className="truncate text-sm font-medium">{website.name}</p>
                              <p className="truncate text-xs text-muted">{website.hostname}</p>
                            </div>
                            <WebsiteStatusBadge status={website.status} />
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="muted-panel mt-5 rounded-xl bg-[#f8f9fc] px-4 py-8 text-center">
                        <div className="mx-auto grid h-11 w-11 place-items-center rounded-xl bg-white text-xl text-brand shadow-sm">
                          ▣
                        </div>
                        <h3 className="mt-3 text-sm font-semibold">No websites yet</h3>
                        <p className="mx-auto mt-1 max-w-sm text-xs leading-5 text-muted">
                          Create a site to provision an isolated static website container.
                        </p>
                      </div>
                    )}
                  </section>

                  <section className="dashboard-card rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft md:p-6">
                    <h2 className="font-bold">Workspace health</h2>
                    <p className="mt-1 text-xs text-muted">Platform service status</p>
                    <div className="mt-5 space-y-4">
                      <HealthRow
                        label="Account security"
                        status="Protected"
                        detail="Secure session enabled"
                      />
                      <HealthRow
                        label="Database"
                        status="Connected"
                        detail="PostgreSQL workspace ready"
                      />
                      <HealthRow
                        label="Website engine"
                        status="Ready"
                        detail="Isolated static containers"
                      />
                    </div>
                  </section>
                </div>

                <section className="dashboard-card mt-6 rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft md:p-6">
                  <div className="flex items-center justify-between">
                    <div>
                      <h2 className="font-bold">Recent activity</h2>
                      <p className="mt-1 text-xs text-muted">Changes in your workspace</p>
                    </div>
                    <span className="rounded-full bg-[#f0efff] px-2.5 py-1 text-[10px] font-bold text-brand">
                      AUDIT LOG
                    </span>
                  </div>
                  {summary?.recentActivity.length ? (
                    <div className="mt-5 divide-y divide-[var(--line)]">
                      {summary.recentActivity.map((activity) => (
                        <div key={activity.id} className="flex items-start gap-3 py-3 first:pt-0">
                          <span className="mt-1 h-2.5 w-2.5 rounded-full bg-brand ring-4 ring-[#f0efff]" />
                          <div className="min-w-0">
                            <p className="text-sm font-medium">{formatActivity(activity.action)}</p>
                            <p className="mt-1 truncate text-xs text-muted">
                              {activity.actor?.email ?? 'System'} ·{' '}
                              {new Date(activity.createdAt).toLocaleString()}
                            </p>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="mt-5 text-sm text-muted">
                      No workspace activity has been recorded yet.
                    </p>
                  )}
                  <div className="mt-5 border-t border-[var(--line)] pt-4 text-xs text-muted">
                    Resource usage will appear when file and monitoring services are enabled.
                  </div>
                </section>
              </>
            )}

            {activeNav === 'Websites' && (
              <div className="space-y-5">
                {profile.role === 'ADMIN' && (
                  <form
                    onSubmit={createWebsite}
                    className="dashboard-card flex flex-col gap-4 rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft sm:flex-row sm:items-end md:p-6"
                  >
                    <label className="min-w-0 flex-1 text-sm font-medium">
                      Website name
                      <input
                        required
                        minLength={2}
                        maxLength={80}
                        value={websiteName}
                        onChange={(event) => setWebsiteName(event.target.value)}
                        className="focus-ring mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface)] px-4 py-3 outline-none"
                        placeholder="My portfolio"
                      />
                    </label>
                    <button
                      disabled={websiteBusy === 'create'}
                      className="focus-ring rounded-xl bg-brand px-5 py-3 text-sm font-semibold text-white disabled:opacity-60"
                    >
                      {websiteBusy === 'create' ? 'Provisioning…' : 'Create website'}
                    </button>
                  </form>
                )}

                <section className="dashboard-card rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft md:p-6">
                  <div className="mb-5">
                    <h2 className="font-bold">Your websites</h2>
                    <p className="mt-1 text-xs text-muted">
                      Static sites run in isolated, resource-limited containers.
                    </p>
                  </div>
                  {websites.length ? (
                    <div className="divide-y divide-[var(--line)]">
                      {websites.map((website) => (
                        <article key={website.id} className="py-4 first:pt-0 last:pb-0">
                          <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
                            <div className="min-w-0 flex-1">
                              <div className="flex flex-wrap items-center gap-2">
                                <h3 className="font-semibold">{website.name}</h3>
                                <WebsiteStatusBadge status={website.status} />
                              </div>
                              <p className="mt-1 break-all text-xs text-muted">
                                {website.hostname}
                              </p>
                              {website.status === 'ACTIVE' && (
                                <a
                                  href={`/preview/${website.slug}/`}
                                  target="_blank"
                                  rel="noopener noreferrer"
                                  className="mt-2 inline-block text-xs font-semibold text-brand hover:underline"
                                >
                                  Preview website
                                </a>
                              )}
                              {website.errorCode && (
                                <p className="mt-1 text-xs text-red-600">
                                  Issue: {website.errorCode}
                                </p>
                              )}
                            </div>
                            {profile.role === 'ADMIN' && (
                              <div className="flex flex-wrap gap-2">
                                {website.status === 'ACTIVE' && (
                                  <>
                                    <SmallAction
                                      disabled={websiteBusy === website.id}
                                      onClick={() => operateWebsite(website, 'stop')}
                                    >
                                      Stop
                                    </SmallAction>
                                    <SmallAction
                                      disabled={websiteBusy === website.id}
                                      onClick={() => operateWebsite(website, 'restart')}
                                    >
                                      Restart
                                    </SmallAction>
                                  </>
                                )}
                                {(website.status === 'STOPPED' || website.status === 'FAILED') && (
                                  <SmallAction
                                    disabled={websiteBusy === website.id}
                                    onClick={() => operateWebsite(website, 'start')}
                                  >
                                    Start
                                  </SmallAction>
                                )}
                                <SmallAction
                                  danger
                                  disabled={
                                    websiteBusy === website.id || isWebsiteChanging(website.status)
                                  }
                                  onClick={() => operateWebsite(website, 'delete')}
                                >
                                  Delete
                                </SmallAction>
                              </div>
                            )}
                          </div>
                        </article>
                      ))}
                    </div>
                  ) : (
                    <p className="rounded-xl bg-[var(--surface-subtle)] px-4 py-8 text-center text-sm text-muted">
                      No websites yet. Create one to start the hosting engine.
                    </p>
                  )}
                </section>
              </div>
            )}

            {!['Overview', 'Websites'].includes(activeNav) && (
              <section className="dashboard-card rounded-2xl border border-[var(--line)] bg-white p-8 text-center shadow-soft">
                <span className="rounded-full bg-[#f0efff] px-3 py-1 text-[10px] font-bold text-brand">
                  COMING IN A LATER PHASE
                </span>
                <h2 className="mt-4 text-lg font-bold">{activeNav} management</h2>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-muted">
                  This section is not enabled yet. It will be connected to verified hosting services
                  before it can make changes to your account.
                </p>
              </section>
            )}
          </div>

          <nav className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-6 border-t border-[var(--line)] bg-[var(--surface)] px-1 pb-[env(safe-area-inset-bottom)] pt-2 lg:hidden">
            {navigation.map((item) => (
              <button
                key={item.label}
                onClick={() => setActiveNav(item.label)}
                className={`focus-ring flex min-w-0 flex-col items-center gap-1 rounded-lg py-2 text-[9px] ${activeNav === item.label ? 'font-bold text-brand' : 'text-muted'}`}
              >
                <span className="text-lg leading-5">{item.icon}</span>
                <span className="truncate">{item.label}</span>
              </button>
            ))}
          </nav>
        </section>
      </div>
    </main>
  );
}

function formatActivity(action: string): string {
  const labels: Record<string, string> = {
    'account.registered': 'Workspace created',
    'auth.login': 'Signed in',
    'auth.logout': 'Signed out',
  };
  return labels[action] ?? action;
}

function isWebsiteChanging(status: Website['status']): boolean {
  return ['PROVISIONING', 'STARTING', 'STOPPING', 'RESTARTING', 'DELETING'].includes(status);
}

function WebsiteStatusBadge({ status }: { status: Website['status'] }) {
  const tone =
    status === 'ACTIVE'
      ? 'bg-[#eafaf1] text-[#159455]'
      : status === 'FAILED'
        ? 'bg-red-50 text-red-700'
        : status === 'STOPPED'
          ? 'bg-[#f2f4f7] text-muted'
          : 'bg-[#fff5e6] text-[#c27a12]';
  const label = status.toLowerCase().replaceAll('_', ' ');
  return (
    <span className={`rounded-full px-2.5 py-1 text-[10px] font-bold capitalize ${tone}`}>
      {label}
    </span>
  );
}

function SmallAction({
  children,
  disabled,
  danger = false,
  onClick,
}: {
  children: ReactNode;
  disabled: boolean;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`focus-ring rounded-lg border px-3 py-2 text-xs font-semibold disabled:opacity-50 ${
        danger
          ? 'border-red-200 text-red-600 hover:bg-red-50'
          : 'border-[var(--line)] text-ink hover:bg-[var(--surface-subtle)]'
      }`}
    >
      {children}
    </button>
  );
}

function BrandMark() {
  return (
    <div className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand text-sm font-black text-white">
      A
    </div>
  );
}

function NavButton({
  item,
  active,
  onClick,
}: {
  item: { label: string; icon: string };
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`nav-link focus-ring flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm ${active ? 'active bg-[#f3f2ff] font-semibold text-brand' : 'text-muted hover:bg-[#f8f9fc] hover:text-ink'}`}
    >
      <span className="w-5 text-center text-base">{item.icon}</span>
      {item.label}
      {item.label === 'Websites' && (
        <span className="ml-auto rounded-md bg-white px-1.5 py-0.5 text-[10px] font-semibold text-muted">
          Soon
        </span>
      )}
    </button>
  );
}

function MetricCard({
  title,
  value,
  note,
  icon,
  tone = 'purple',
}: {
  title: string;
  value: number | string;
  note: string;
  icon: string;
  tone?: 'purple' | 'green' | 'blue' | 'amber';
}) {
  const iconTones = {
    purple: 'bg-[#f0efff] text-brand',
    green: 'bg-[#eafaf1] text-[#159455]',
    blue: 'bg-[#eaf4ff] text-[#2476d2]',
    amber: 'bg-[#fff5e6] text-[#c27a12]',
  };
  return (
    <article className="dashboard-card rounded-2xl border border-[var(--line)] bg-white p-5 shadow-soft">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-medium text-muted">{title}</p>
          <p className="mt-3 text-3xl font-bold tracking-tight">{value}</p>
        </div>
        <span className={`grid h-10 w-10 place-items-center rounded-xl text-lg ${iconTones[tone]}`}>
          {icon}
        </span>
      </div>
      <p className="mt-3 text-[11px] text-muted">{note}</p>
    </article>
  );
}

function HealthRow({
  label,
  status,
  detail,
  pending = false,
}: {
  label: string;
  status: string;
  detail: string;
  pending?: boolean;
}) {
  return (
    <div className="flex items-center gap-3">
      <span
        className={`h-2 w-2 shrink-0 rounded-full ${pending ? 'bg-amber-400' : 'bg-emerald-500'}`}
      />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{label}</p>
        <p className="mt-0.5 truncate text-[11px] text-muted">{detail}</p>
      </div>
      <span
        className={`text-[10px] font-semibold ${pending ? 'text-amber-600' : 'text-emerald-600'}`}
      >
        {status}
      </span>
    </div>
  );
}
