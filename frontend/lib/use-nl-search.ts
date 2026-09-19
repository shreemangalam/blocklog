"use client";

import { useCallback, useEffect, useState } from "react";
import type { SearchRequest } from "./types";

export type NLSearchStatus =
  | "idle"
  | "translating"
  | "success"
  | "timeout"
  | "network_error"
  | "parse_error"
  | "unavailable";

export interface NLSearchState {
  status: NLSearchStatus;
  parsedQuery: Partial<SearchRequest> | null;
  errorMessage: string | null;
}

function validateParsedQuery(candidate: unknown): Partial<SearchRequest> {
  if (typeof candidate !== "object" || candidate === null) {
    throw new Error("Response was not a JSON object");
  }
  const obj = candidate as Record<string, unknown>;

  if (typeof obj.tenant_id !== "string" || !obj.tenant_id.trim()) {
    throw new Error("Missing or invalid tenant_id");
  }
  if (typeof obj.from !== "string" || Number.isNaN(Date.parse(obj.from))) {
    throw new Error("Missing or invalid from timestamp");
  }
  if (typeof obj.to !== "string" || Number.isNaN(Date.parse(obj.to))) {
    throw new Error("Missing or invalid to timestamp");
  }
  if (Date.parse(obj.from) >= Date.parse(obj.to)) {
    throw new Error("from must be before to");
  }

  const result: Partial<SearchRequest> = {
    tenant_id: obj.tenant_id,
    from: obj.from,
    to: obj.to,
  };
  if (obj.tags && typeof obj.tags === "object") {
    result.tags = obj.tags as Record<string, string>;
  }
  if (typeof obj.text === "string" && obj.text) {
    result.text = obj.text;
  }
  if (typeof obj.limit === "number") {
    result.limit = obj.limit;
  }
  if (typeof obj.timeout_ms === "number") {
    result.timeout_ms = obj.timeout_ms;
  }
  return result;
}

export function useNLSearch() {
  const [state, setState] = useState<NLSearchState>({
    status: "idle",
    parsedQuery: null,
    errorMessage: null,
  });
  const [isConfigured, setIsConfigured] = useState(false);

  useEffect(() => {
    fetch("/api/nl-translate")
      .then((r) => r.json())
      .then((data) => setIsConfigured(data.enabled === true))
      .catch(() => setIsConfigured(false));
  }, []);

  const translate = useCallback(async (query: string) => {
    setState({ status: "translating", parsedQuery: null, errorMessage: null });

    try {
      const response = await fetch("/api/nl-translate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query }),
      });

      if (response.status === 503) {
        setState({
          status: "unavailable",
          parsedQuery: null,
          errorMessage: "Natural-language search is not configured in this deployment.",
        });
        return;
      }

      if (response.status === 504) {
        setState({
          status: "timeout",
          parsedQuery: null,
          errorMessage: "Translation timed out. Try a shorter query, or use the structured form.",
        });
        return;
      }

      if (!response.ok) {
        throw new Error(`Server returned ${response.status}`);
      }

      const data = await response.json();
      const text: string = data?.text ?? "";

      let candidate: unknown;
      try {
        candidate = JSON.parse(text);
      } catch {
        setState({
          status: "parse_error",
          parsedQuery: null,
          errorMessage: "Couldn't interpret that query. Try rephrasing, or use the structured form.",
        });
        return;
      }

      const parsed = validateParsedQuery(candidate);
      setState({ status: "success", parsedQuery: parsed, errorMessage: null });
    } catch {
      setState({
        status: "network_error",
        parsedQuery: null,
        errorMessage: "Could not reach the translation service. Use the structured form.",
      });
    }
  }, []);

  const reset = useCallback(() => {
    setState({ status: "idle", parsedQuery: null, errorMessage: null });
  }, []);

  return { state, translate, reset, isConfigured };
}
