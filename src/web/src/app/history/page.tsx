import type { Metadata } from "next";

import { HistoryOverviewPage } from "@/components/history-overview-page";

export const metadata: Metadata = {
  title: "Session History",
  description: "Browse saved SpeechPilot sessions and reopen transcript and coaching detail.",
};

export default function HistoryPage() {
  return <HistoryOverviewPage />;
}