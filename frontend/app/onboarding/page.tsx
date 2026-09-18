"use client";

import { useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CheckCircle2, Circle, ArrowRight } from "lucide-react";
import { ingestLogs, searchLogs, ApiError } from "@/lib/api";
import type { SearchResponse } from "@/lib/types";

const DEMO_TENANT = "onboarding-demo";

function sampleBatch() {
  const now = Date.now();
  return {
    tenant_id: DEMO_TENANT,
    records: [
      {
        timestamp: new Date(now - 90_000).toISOString(),
        tags: { env: "prod", service: "payments" },
        message: "payment failed for order 8842: gateway timeout",
      },
      {
        timestamp: new Date(now - 60_000).toISOString(),
        tags: { env: "prod", service: "payments" },
        message: "payment succeeded for order 8843",
      },
      {
        timestamp: new Date(now - 30_000).toISOString(),
        tags: { env: "prod", service: "auth" },
        message: "login rate limit exceeded for ip 203.0.113.5",
      },
    ],
  };
}

function StepBadge({ done }: { done: boolean }) {
  return done ? (
    <CheckCircle2 className="size-5 text-emerald-600" />
  ) : (
    <Circle className="size-5 text-muted-foreground" />
  );
}

export default function OnboardingPage() {
  const [ingesting, setIngesting] = useState(false);
  const [ingested, setIngested] = useState(false);
  const [ingestError, setIngestError] = useState<string | null>(null);

  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<SearchResponse | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  async function runIngest() {
    setIngesting(true);
    setIngestError(null);
    try {
      await ingestLogs(sampleBatch());
      setIngested(true);
    } catch (err) {
      setIngestError(err instanceof ApiError ? err.message : "Ingestion failed.");
    } finally {
      setIngesting(false);
    }
  }

  async function runSearch() {
    setSearching(true);
    setSearchError(null);
    try {
      const now = new Date();
      const from = new Date(now.getTime() - 10 * 60 * 1000);
      const result = await searchLogs({
        tenant_id: DEMO_TENANT,
        from: from.toISOString(),
        to: now.toISOString(),
        tags: { env: "prod" },
      });
      setSearchResult(result);
    } catch (err) {
      setSearchError(err instanceof ApiError ? err.message : "Search failed.");
    } finally {
      setSearching(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Onboarding</h1>
        <p className="text-sm text-muted-foreground">
          A three-minute walkthrough: ingest a small synthetic batch, wait for it to
          publish, then search it with both interfaces.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <StepBadge done={ingested} />
            Step 1 — Submit a synthetic batch
          </CardTitle>
          <CardDescription>
            Sends three records under tenant <code>{DEMO_TENANT}</code>. The response is{" "}
            <code>202 buffered</code> — accepted into memory, not yet searchable.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Button onClick={runIngest} disabled={ingesting || ingested}>
            {ingested ? "Batch submitted" : ingesting ? "Submitting…" : "Submit batch"}
          </Button>
          {ingestError && (
            <Alert variant="destructive">
              <AlertTitle>Could not submit batch</AlertTitle>
              <AlertDescription>{ingestError}</AlertDescription>
            </Alert>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <StepBadge done={searchResult !== null} />
            Step 2 — Wait for persistence, then search
          </CardTitle>
          <CardDescription>
            Blocks publish on a 5-second age trigger or a 5MB size trigger, whichever comes
            first. Wait a few seconds after step 1, then run the example search below — it
            filters tenant <code>{DEMO_TENANT}</code>, tag <code>env=prod</code>, last 10 minutes.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <Button onClick={runSearch} disabled={!ingested || searching} variant="outline">
            {searching ? "Searching…" : "Run example search"}
          </Button>
          {searchError && (
            <Alert variant="destructive">
              <AlertTitle>Search failed</AlertTitle>
              <AlertDescription>{searchError}</AlertDescription>
            </Alert>
          )}
          {searchResult && (
            <div className="space-y-2 rounded-lg border border-border p-3">
              <p className="text-xs text-muted-foreground">
                {searchResult.returned_count} match(es) · {searchResult.elapsed_ms}ms ·{" "}
                {searchResult.scanned_blocks}/{searchResult.candidate_blocks} blocks scanned
                {searchResult.partial && (
                  <Badge variant="outline" className="ml-2 border-amber-500 text-amber-700">
                    Partial — try again in a moment, the block may not be published yet
                  </Badge>
                )}
              </p>
              {searchResult.results.map((hit, i) => (
                <div key={i} className="rounded border border-border/60 bg-muted/30 p-2 text-xs font-mono">
                  {hit.timestamp} — {hit.message}
                </div>
              ))}
              {searchResult.returned_count === 0 && (
                <p className="text-xs text-muted-foreground">
                  No results yet — the batch may still be buffered. Wait a few seconds and
                  run the search again.
                </p>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Step 3 — Try natural language</CardTitle>
          <CardDescription>
            The full search page also has a Natural Language tab. In a deployment with a
            translation service configured, typing something like:
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="rounded-lg border border-border bg-muted/30 p-3 font-mono text-xs">
            &quot;show me {DEMO_TENANT} payment errors from the last 10 minutes&quot;
          </div>
          <p className="text-sm text-muted-foreground">
            would generate the same structured query as step 2, shown as an editable preview
            before it runs. This deployment ships that UI and logic but has no translation
            service configured, so the tab explains that and defers to the structured form —
            covered in <Link href="/help" className="underline">Help</Link>.
          </p>
          <Button asChild variant="outline">
            <Link href="/">
              Go to the search page <ArrowRight className="size-4" />
            </Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
