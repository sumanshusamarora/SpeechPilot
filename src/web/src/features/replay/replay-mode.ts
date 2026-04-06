export const replayFeature = {
  status: "available",
  description:
    "Replay mode accepts a local 16-bit PCM WAV upload and pushes it through the same backend transcription pipeline used by live websocket audio.",
  plannedInputs: ["local WAV upload", "fixture catalog", "contract replay"],
} as const;