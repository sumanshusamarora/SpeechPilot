import type { Metadata } from "next";

import { SettingsPage } from "@/components/settings-page";

export const metadata: Metadata = {
  title: "Settings",
  description: "Tune local SpeechPilot web preferences and deployment environment configuration.",
};

export default function SettingsRoutePage() {
  return <SettingsPage />;
}