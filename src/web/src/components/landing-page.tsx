import Link from "next/link";

export function LandingPage() {
  return (
    <main className="page-shell">
      <section className="hero-card hero-card-landing">
        <div className="hero-copy">
          <span className="eyebrow">Realtime Speaking Coach</span>
          <h1>SpeechPilot helps speakers improve pace, delivery, and confidence in real time.</h1>
          <p>
            Practice live with websocket coaching, replay recorded sessions through the same
            backend pipeline, and review session history in a product UI built for repeated,
            high-signal speaking drills.
          </p>
          <div className="pill-row">
            <span className="pill">Live microphone coaching</span>
            <span className="pill">Replay analysis</span>
            <span className="pill">Session intelligence</span>
          </div>
          <div className="action-row">
            <Link className="button button-primary" href="/live">
              Start live coaching
            </Link>
            <Link className="button button-secondary" href="/replay">
              Analyze a recording
            </Link>
          </div>
        </div>

        <div className="hero-stat-grid">
          <article className="hero-stat-card">
            <span>Realtime pipeline</span>
            <strong>Websocket transcript, pace, and coaching updates in one loop.</strong>
          </article>
          <article className="hero-stat-card">
            <span>Replay parity</span>
            <strong>Recorded audio runs through the same event model used for live sessions.</strong>
          </article>
          <article className="hero-stat-card">
            <span>Review surface</span>
            <strong>History, details, transcript segments, and coaching timelines stay aligned.</strong>
          </article>
        </div>
      </section>

      <section className="feature-grid">
        <article className="surface-card">
          <span className="eyebrow">Live</span>
          <h2>Coach delivery while you speak</h2>
          <p>
            Open a live session, stream microphone audio to the backend, and watch transcript,
            pace band, confidence, and coaching advice update as your delivery settles.
          </p>
        </article>
        <article className="surface-card">
          <span className="eyebrow">Replay</span>
          <h2>Inspect a talk after the fact</h2>
          <p>
            Upload a WAV file to replay the same session pipeline without a live microphone.
            Compare transcript structure, pace band changes, and final summary in one pass.
          </p>
        </article>
        <article className="surface-card">
          <span className="eyebrow">History</span>
          <h2>Build a reliable review habit</h2>
          <p>
            Session history keeps runs easy to revisit. Open a detail page to compare transcript
            segments, average pace, and coaching timelines before the next rehearsal.
          </p>
        </article>
      </section>

      <section className="story-grid">
        <article className="surface-card surface-card-emphasis">
          <h2>Built for serious practice loops</h2>
          <p>
            SpeechPilot is designed for speakers who want a tighter feedback loop than a generic
            recorder. It gives you live direction, structured replay, and enough telemetry to
            understand whether pace issues came from nerves, drift, or inconsistent delivery.
          </p>
        </article>
        <article className="surface-card">
          <h2>Ready for deployment</h2>
          <p>
            The web app runs as a clean Next.js frontend with explicit backend HTTP and websocket
            configuration, static metadata, and route-level flows that map cleanly to Vercel.
          </p>
          <div className="action-row compact-actions">
            <Link className="button button-secondary" href="/history">
              Browse session history
            </Link>
            <Link className="button button-secondary" href="/settings">
              View web settings
            </Link>
          </div>
        </article>
      </section>
    </main>
  );
}