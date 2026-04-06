import type { Metadata } from "next";

import { SessionDetailPage } from "@/components/session-detail-page";

export function generateMetadata({ params }: { params: { sessionId: string } }): Metadata {
  return {
    title: `Session ${params.sessionId}`,
    description: `Review transcript and coaching detail for SpeechPilot session ${params.sessionId}.`,
  };
}

export default function SessionHistoryDetailRoute({
  params,
}: {
  params: { sessionId: string };
}) {
  return <SessionDetailPage sessionId={decodeURIComponent(params.sessionId)} />;
}