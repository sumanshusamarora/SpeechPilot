"use client";

import { useEffect, useState } from "react";

const STORAGE_KEY = "speechpilot.web.preferences.v1";

export interface AppPreferences {
  sessionPrefix: string;
  autoConnectLive: boolean;
  showDiagnosticsByDefault: boolean;
  practiceFocus: "presentations" | "interviews" | "storytelling";
}

const defaultPreferences: AppPreferences = {
  sessionPrefix: "live",
  autoConnectLive: false,
  showDiagnosticsByDefault: false,
  practiceFocus: "presentations",
};

export function useAppPreferences() {
  const [preferences, setPreferences] = useState<AppPreferences>(defaultPreferences);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    try {
      const storedValue = window.localStorage.getItem(STORAGE_KEY);
      if (storedValue) {
        const parsed = JSON.parse(storedValue) as Partial<AppPreferences>;
        setPreferences({
          ...defaultPreferences,
          ...parsed,
        });
      }
    } catch {
      window.localStorage.removeItem(STORAGE_KEY);
    } finally {
      setHydrated(true);
    }
  }, []);

  useEffect(() => {
    if (!hydrated) {
      return;
    }

    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
  }, [hydrated, preferences]);

  function updatePreference<Key extends keyof AppPreferences>(
    key: Key,
    value: AppPreferences[Key],
  ) {
    setPreferences((current) => ({
      ...current,
      [key]: value,
    }));
  }

  function resetPreferences() {
    setPreferences(defaultPreferences);
  }

  return {
    hydrated,
    preferences,
    resetPreferences,
    updatePreference,
  };
}