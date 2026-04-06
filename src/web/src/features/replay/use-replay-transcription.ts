"use client";

import { useState } from "react";

import type {
  SessionSummaryPayload,
  TranscriptFinalEvent,
  TranscriptPartialEvent,
} from "@/lib/contracts";
import { env } from "@/lib/env";

export interface ReplayTranscriptionResult {
  sessionId: string;
  provider: string;
  summary: SessionSummaryPayload;
  events: Array<TranscriptPartialEvent | TranscriptFinalEvent>;
}

type ReplayStatus = "idle" | "uploading" | "completed" | "error";

export function useReplayTranscription() {
  const [status, setStatus] = useState<ReplayStatus>("idle");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<ReplayTranscriptionResult | null>(null);

  const runReplay = async (file: File) => {
    setStatus("uploading");
    setErrorMessage(null);

    const formData = new FormData();
    formData.append("file", file);

    try {
      const response = await fetch(`${env.backendHttpUrl}/api/replay/transcribe`, {
        method: "POST",
        body: formData,
      });

      const payload = (await response.json()) as ReplayTranscriptionResult | { detail: string };
      if (!response.ok) {
        throw new Error("detail" in payload ? payload.detail : "Replay transcription failed.");
      }

      setResult(payload as ReplayTranscriptionResult);
      setStatus("completed");
    } catch (error) {
      setStatus("error");
      setResult(null);
      setErrorMessage(error instanceof Error ? error.message : "Replay transcription failed.");
    }
  };

  return {
    errorMessage,
    result,
    runReplay,
    status,
  };
}