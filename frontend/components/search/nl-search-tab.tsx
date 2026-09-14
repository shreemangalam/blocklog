"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Sparkles, Clock3 } from "lucide-react";
import { useNLSearch } from "@/lib/use-nl-search";
import { StructuredSearchForm } from "./structured-search-form";
import type { SearchRequest } from "@/lib/types";

interface NLSearchTabProps {
  onExecute: (request: SearchRequest) => void;
  loading: boolean;
}

export function NLSearchTab({ onExecute, loading }: NLSearchTabProps) {
  const [query, setQuery] = useState("");
  const { state, translate, reset, isConfigured } = useNLSearch();

  if (!isConfigured) {
    return (
      <Card className="border-dashed">
        <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
          <Badge variant="secondary" className="gap-1.5">
            <Clock3 className="size-3.5" />
            Coming in a future version
          </Badge>
          <Sparkles className="size-6 text-muted-foreground" />
          <h3 className="text-sm font-semibold">Natural-language search isn&apos;t enabled yet</h3>
          <p className="max-w-md text-sm text-muted-foreground">
            This deployment ships the full NL-search UI and translation logic, but no
            language-model API key is configured, so the feature stays inactive. Once
            an operator sets <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">
              NEXT_PUBLIC_ANTHROPIC_API_KEY
            </code>{" "}
            in <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">.env.local</code>,
            this tab will translate plain-English queries (e.g. &quot;payment errors in the
            last 2 hours&quot;) into the structured search below, with an editable preview
            before running.
          </p>
          <p className="text-xs text-muted-foreground">
            Use the structured form for now — it covers every query this mode would generate.
          </p>
        </CardContent>
      </Card>
    );
  }

  async function handleTranslate(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    await translate(query.trim());
  }

  return (
    <div className="space-y-4">
      <form onSubmit={handleTranslate} className="space-y-1.5">
        <Label htmlFor="nl-query">Describe what you&apos;re looking for</Label>
        <div className="flex gap-2">
          <Input
            id="nl-query"
            placeholder="show me payment-service errors from the last 2 hours with tag env=prod"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            disabled={state.status === "translating"}
          />
          <Button type="submit" disabled={state.status === "translating" || !query.trim()}>
            {state.status === "translating" ? "Translating…" : "Translate"}
          </Button>
        </div>
      </form>

      {state.status === "translating" && (
        <p className="text-sm text-muted-foreground">Translating your query… (up to 8s)</p>
      )}

      {(state.status === "timeout" ||
        state.status === "network_error" ||
        state.status === "parse_error") && (
        <Alert variant="destructive">
          <AlertTitle>Couldn&apos;t translate that query</AlertTitle>
          <AlertDescription>{state.errorMessage}</AlertDescription>
        </Alert>
      )}

      {state.status === "success" && state.parsedQuery && (
        <div className="space-y-3 rounded-lg border border-border p-4">
          <div className="flex items-center justify-between">
            <h4 className="text-sm font-semibold">Generated query — review and edit before running</h4>
            <Button variant="ghost" size="sm" onClick={reset}>
              Start over
            </Button>
          </div>
          <StructuredSearchForm onSubmit={onExecute} loading={loading} initial={state.parsedQuery} />
        </div>
      )}
    </div>
  );
}
