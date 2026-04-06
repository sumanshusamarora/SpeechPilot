import type { Metadata } from "next";

import "./globals.css";

export const metadata: Metadata = {
  title: "SpeechPilot v2 Debug Shell",
  description: "Developer surface for SpeechPilot backend websocket sessions.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}