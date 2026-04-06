import type { Metadata } from "next";

import { LiveSessionPage } from "@/components/live-session-page";

export const metadata: Metadata = {
  title: "Live Coaching",
  description: "Stream live microphone audio to SpeechPilot and receive real-time transcript and pace coaching.",
};

export default function LivePage() {
  return <LiveSessionPage />;
}