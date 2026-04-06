export const replayFeature = {
  status: "reserved",
  description:
    "Recorded-audio replay is intentionally deferred. This feature boundary exists so fixture-driven sessions can be added without reshaping the app shell.",
  plannedInputs: ["local fixture upload", "sample catalog", "contract replay"],
} as const;