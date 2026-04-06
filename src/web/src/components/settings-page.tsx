"use client";

import Link from "next/link";

import { useAppPreferences } from "@/hooks/use-app-preferences";
import { env } from "@/lib/env";

export function SettingsPage() {
  const { preferences, resetPreferences, updatePreference } = useAppPreferences();

  return (
    <main className="page-shell">
      <section className="hero-card hero-card-compact">
        <div className="hero-copy">
          <span className="eyebrow">Web Settings</span>
          <h1>Adjust how the SpeechPilot web client behaves during practice and review.</h1>
          <p>
            These settings are local to this browser. They tune how the UI behaves around live
            sessions and diagnostics without changing the backend contract or persisted session data.
          </p>
        </div>
        <div className="action-row compact-actions">
          <Link className="button button-secondary" href="/live">
            Open live coaching
          </Link>
          <button className="button button-danger" onClick={resetPreferences}>
            Reset preferences
          </button>
        </div>
      </section>

      <section className="content-grid">
        <article className="surface-card surface-card-emphasis">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Practice</span>
              <h2>Experience defaults</h2>
            </div>
          </div>

          <label className="field">
            <span>Session prefix</span>
            <input
              value={preferences.sessionPrefix}
              onChange={(event) => updatePreference("sessionPrefix", event.target.value)}
            />
          </label>

          <label className="field">
            <span>Practice focus</span>
            <select
              value={preferences.practiceFocus}
              onChange={(event) => {
                updatePreference(
                  "practiceFocus",
                  event.target.value as typeof preferences.practiceFocus,
                );
              }}
            >
              <option value="presentations">Presentations</option>
              <option value="interviews">Interviews</option>
              <option value="storytelling">Storytelling</option>
            </select>
          </label>

          <label className="toggle-row">
            <div>
              <strong>Auto-connect live websocket</strong>
              <span>Immediately open the websocket when you visit the live page.</span>
            </div>
            <input
              checked={preferences.autoConnectLive}
              onChange={(event) => updatePreference("autoConnectLive", event.target.checked)}
              type="checkbox"
            />
          </label>

          <label className="toggle-row">
            <div>
              <strong>Open diagnostics by default</strong>
              <span>Expand the collapsible diagnostics surface on live pages automatically.</span>
            </div>
            <input
              checked={preferences.showDiagnosticsByDefault}
              onChange={(event) =>
                updatePreference("showDiagnosticsByDefault", event.target.checked)
              }
              type="checkbox"
            />
          </label>
        </article>

        <article className="surface-card">
          <div className="surface-header">
            <div>
              <span className="eyebrow">Environment</span>
              <h2>Deployment wiring</h2>
            </div>
          </div>

          <div className="data-grid">
            <div className="data-point">
              <span>Backend HTTP URL</span>
              <strong>{env.backendHttpUrl}</strong>
            </div>
            <div className="data-point">
              <span>Backend websocket URL</span>
              <strong>{env.backendWsUrl}</strong>
            </div>
            <div className="data-point">
              <span>Site URL</span>
              <strong>{env.siteUrl}</strong>
            </div>
          </div>

          <div className="keyline-list">
            <div className="keyline-item">Set NEXT_PUBLIC_BACKEND_HTTP_URL for API requests</div>
            <div className="keyline-item">Set NEXT_PUBLIC_BACKEND_WS_URL for live microphone streaming</div>
            <div className="keyline-item">Set NEXT_PUBLIC_SITE_URL so metadata and social previews resolve correctly</div>
          </div>
        </article>
      </section>
    </main>
  );
}