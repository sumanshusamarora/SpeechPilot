import type { Metadata } from "next";
import Link from "next/link";

import "./globals.css";

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "https://speechpilot.app";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "SpeechPilot",
    template: "%s | SpeechPilot",
  },
  description: "AI-powered speaking coach for live pace guidance, replay analysis, and session review.",
  keywords: [
    "speech coaching",
    "presentation practice",
    "pace feedback",
    "realtime transcript",
    "public speaking",
  ],
  openGraph: {
    title: "SpeechPilot",
    description: "AI-powered speaking coach for live pace guidance, replay analysis, and session review.",
    siteName: "SpeechPilot",
    type: "website",
    url: siteUrl,
  },
  twitter: {
    card: "summary_large_image",
    title: "SpeechPilot",
    description: "AI-powered speaking coach for live pace guidance, replay analysis, and session review.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>
        <div className="site-shell">
          <header className="site-header">
            <Link className="brand-lockup" href="/">
              <span className="brand-mark">SP</span>
              <span className="brand-copy">
                <strong>SpeechPilot</strong>
                <span>Realtime speaking coach</span>
              </span>
            </Link>
            <nav className="site-nav">
              <Link href="/">Home</Link>
              <Link href="/live">Live</Link>
              <Link href="/replay">Replay</Link>
              <Link href="/history">History</Link>
              <Link href="/settings">Settings</Link>
            </nav>
          </header>
          {children}
          <footer className="site-footer">
            <span>SpeechPilot</span>
            <span>Practice with clearer pacing, calmer delivery, and better review loops.</span>
          </footer>
        </div>
      </body>
    </html>
  );
}