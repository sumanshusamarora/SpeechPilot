import type { Metadata } from "next";

import { ReplayPage } from "@/components/replay-page";

export const metadata: Metadata = {
  title: "Replay Analysis",
  description: "Upload recorded audio for transcript, pace, and coaching review in SpeechPilot.",
};

export default function ReplayRoutePage() {
  return <ReplayPage />;
}