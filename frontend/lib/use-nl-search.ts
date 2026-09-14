"use client";

import { useCallback, useState } from "react";
import type { SearchRequest } from "./types";

const NL_TIMEOUT_MS = 8000;
const LLM_MODEL = "claude-sonnet-4-6";
const KNOWN_TENANTS = ["acme", "globex", "initech"];

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

function buildSystemPrompt(nowIso: string): string {
  return [
    "You translate a natural-language incident-response query into a JSON object matching this exact schema for POST /api/v1/search:",
    "{",
    '  "tenant_id": string (required),',
    '  "from": ISO-8601 UTC timestamp (required),',
    '  "to": ISO-8601 UTC timestamp (required, must be after from),',
    '  "tags": object of exact string key/value pairs (optional),',
    '  "text": string literal substring, case-sensitive (optional),',
    '  "limit": integer 1-1000 (optional, default 100),',
    '  "timeout_ms": integer 1-30000 (optional, default 5000)',
    "}",
    `The current UTC time is ${nowIso}. Resolve relative time expressions ("last 2 hours", "yesterday") against this instant.`,
    `Known tenant identifiers: ${KNOWN_TENANTS.join(", ")}. Match informal references to the closest known tenant.`,
    "Respond with valid JSON only. No markdown fencing, no explanation, no extra keys.",
  ].join("\n");
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

/**
 * Frontend-only NL-to-structured-query translation, per docs/public/technical-design.md.
 * Gated on NEXT_PUBLIC_ANTHROPIC_API_KEY: with no key configured this never makes a
 * network call and reports status "unavailable" so callers can render a future-version notice.
 */
export function useNLSearch() {
  const [state, setState] = useState<NLSearchState>({
    status: "idle",
    parsedQuery: null,
    errorMessage: null,
  });

  const apiKey = process.env.NEXT_PUBLIC_ANTHROPIC_API_KEY;

  const translate = useCallback(
    async (query: string) => {
      if (!apiKey) {
        setState({
          status: "unavailable",
          parsedQuery: null,
          errorMessage: "Natural-language search is not configured in this deployment.",
        });
        return;
      }

      setState({ status: "translating", parsedQuery: null, errorMessage: null });

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), NL_TIMEOUT_MS);

      try {
        const nowIso = new Date().toISOString();
        const response = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST",
          signal: controller.signal,
          headers: {
            "Content-Type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
            "anthropic-dangerous-direct-browser-access": "true",
          },
          body: JSON.stringify({
            model: LLM_MODEL,
            max_tokens: 512,
            system: buildSystemPrompt(nowIso),
            messages: [{ role: "user", content: query }],
          }),
        });

        if (!response.ok) {
          throw new Error(`LLM API returned ${response.status}`);
        }

        const data = await response.json();
        const text: string = data?.content?.[0]?.text ?? "";

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
      } catch (err) {
        if (err instanceof DOMException && err.name === "AbortError") {
          setState({
            status: "timeout",
            parsedQuery: null,
            errorMessage: "Translation timed out. Try a shorter query, or use the structured form.",
          });
        } else {
          setState({
            status: "network_error",
            parsedQuery: null,
            errorMessage: "Could not reach the translation service. Use the structured form.",
          });
        }
      } finally {
        clearTimeout(timer);
      }
    },
    [apiKey]
  );

  const reset = useCallback(() => {
    setState({ status: "idle", parsedQuery: null, errorMessage: null });
  }, []);

  return { state, translate, reset, isConfigured: Boolean(apiKey) };
}
