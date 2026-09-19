"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CheckCircle2, Circle, ArrowRight, RotateCcw } from "lucide-react";
import { ingestLogs, searchLogs, ApiError } from "@/lib/api";
import type { IngestRequest, SearchRequest, SearchResponse } from "@/lib/types";

const DEMO_TENANT = "onboarding-demo";
const FLUSH_WAIT_SECONDS = 6;

function sampleBatch(): IngestRequest {
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

function sampleSearch(): SearchRequest {
  const now = new Date();
  const from = new Date(now.getTime() - 60 * 60 * 1000);
  return {
    tenant_id: DEMO_TENANT,
    from: from.toISOString(),
    to: now.toISOString(),
    tags: { env: "prod" },
  };
}

function StepBadge({ done }: { done: boolean }) {
  return done ? (
    <CheckCircle2 className="size-5 text-emerald-600" />
  ) : (
    <Circle className="size-5 text-muted-foreground" />
  );
}

function JsonBlock({ label, value }: { label: string; value: unknown }) {
  return (
    <div className="rounded-md border border-border bg-muted/40 p-3">
      <div className="mb-1 text-xs font-medium text-foreground">{label}</div>
      <pre className="overflow-x-auto whitespace-pre-wrap font-mono text-[11px] leading-relaxed">
{JSON.stringify(value, null, 2)}
      </pre>
    </div>
  );
}

function TagChip({ k, v }: { k: string; v: string }) {
  return (
    <span className="inline-flex items-center rounded border border-border/70 bg-muted/60 px-1.5 py-0.5 font-mono text-[10px] leading-none text-foreground">
      {k}={v}
    </span>
  );
}

export default function OnboardingPage() {
  const [ingesting, setIngesting] = useState(false);
  const [ingested, setIngested] = useState(false);
  const [ingestBatch, setIngestBatch] = useState<IngestRequest | null>(null);
  const [ingestError, setIngestError] = useState<string | null>(null);

  const [countdown, setCountdown] = useState<number | null>(null);

  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<SearchResponse | null>(null);
  const [searchQuery, setSearchQuery] = useState<SearchRequest | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  // 6-second countdown after ingest so the user knows to wait for age-flush.
  useEffect(() => {
    if (countdown === null || countdown <= 0) return;
    const timer = setTimeout(() => setCountdown(countdown - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown]);

  const runIngest = useCallback(async () => {
    setIngesting(true);
    setIngestError(null);
    const batch = sampleBatch();
    setIngestBatch(batch);
    try {
      await ingestLogs(batch);
      setIngested(true);
      setCountdown(FLUSH_WAIT_SECONDS);
    } catch (err) {
      setIngestError(err instanceof ApiError ? err.message : "Ingestion failed.");
    } finally {
      setIngesting(false);
    }
  }, []);

  const runSearch = useCallback(async () => {
    setSearching(true);
    setSearchError(null);
    const query = sampleSearch();
    setSearchQuery(query);
    try {
      const result = await searchLogs(query);
      setSearchResult(result);
    } catch (err) {
      setSearchError(err instanceof ApiError ? err.message : "Search failed.");
    } finally {
      setSearching(false);
    }
  }, []);

  const reset = useCallback(() => {
    setIngesting(false);
    setIngested(false);
    setIngestBatch(null);
    setIngestError(null);
    setCountdown(null);
    setSearching(false);
    setSearchResult(null);
    setSearchQuery(null);
    setSearchError(null);
  }, []);

  const searchReady = ingested && (countdown === null || countdown <= 0);
  const anyProgress = ingested || ingestError !== null;

  const nlPreview = useMemo(
    () => ({
      tenant_id: DEMO_TENANT,
      from: "<computed from 'last 10 minutes'>",
      to: "<computed from 'now'>",
      tags: { env: "prod" },
      text: "payment",
      limit: 100,
      timeout_ms: 5000,
    }),
    [],
  );

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Onboarding</h1>
          <p className="text-sm text-muted-foreground">
            A three-minute walkthrough. Ingest a small synthetic batch, wait for it to publish,
            then search it. Every step shows the request and response so you can see what the
            engine actually did.
          </p>
        </div>
        {anyProgress && (
          <Button variant="ghost" size="sm" onClick={reset} className="shrink-0">
            <RotateCcw className="size-4" />
            <span className="ml-1">Reset</span>
          </Button>
        )}
      </div>

      {/* STEP 1 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <StepBadge done={ingested} />
            Step 1. Submit a synthetic batch
          </CardTitle>
          <CardDescription>
            Sends three records under tenant <code>{DEMO_TENANT}</code>. The response is{" "}
            <code>202 buffered</code>. Accepted into memory, not yet searchable.
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
          {ingestBatch && (
            <JsonBlock label="POST /api/v1/logs" value={ingestBatch} />
          )}
          {ingested && (
            <div className="text-xs text-muted-foreground">
              The backend returned <code>202 buffered</code> and the records went onto a
              per-tenant buffer. Next: wait a few seconds for the age-flush trigger to
              publish the block.
            </div>
          )}
        </CardContent>
      </Card>

      {/* STEP 2 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <StepBadge done={searchResult !== null && searchResult.returned_count > 0} />
            Step 2. Wait for persistence, then search
          </CardTitle>
          <CardDescription>
            Blocks publish on a 5-second age trigger or a 5 MB size trigger, whichever comes
            first. This search filters tenant <code>{DEMO_TENANT}</code>, tag{" "}
            <code>env=prod</code>, last one hour.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3">
            <Button
              onClick={runSearch}
              disabled={!searchReady || searching}
              variant="outline"
            >
              {searching ? "Searching…" : "Run example search"}
            </Button>
            {countdown !== null && countdown > 0 && (
              <span className="text-xs text-muted-foreground">
                Auto-ready in {countdown}s (waiting for age-flush)…
              </span>
            )}
            {countdown === 0 && !searchResult && (
              <span className="text-xs text-emerald-700">
                Ready. The block should be published now.
              </span>
            )}
          </div>

          {searchError && (
            <Alert variant="destructive">
              <AlertTitle>Search failed</AlertTitle>
              <AlertDescription>{searchError}</AlertDescription>
            </Alert>
          )}

          {searchQuery && (
            <JsonBlock label="POST /api/v1/search" value={searchQuery} />
          )}

          {searchResult && (
            <div className="space-y-3">
              {/* Response summary strip */}
              <div className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/30 p-2 text-xs">
                <span className="font-medium">
                  {searchResult.returned_count} match{searchResult.returned_count === 1 ? "" : "es"}
                </span>
                <span className="text-muted-foreground">·</span>
                <span>{searchResult.elapsed_ms} ms engine time</span>
                <span className="text-muted-foreground">·</span>
                <span>
                  {searchResult.scanned_blocks}/{searchResult.candidate_blocks} blocks scanned
                </span>
                {searchResult.unavailable_blocks > 0 && (
                  <>
                    <span className="text-muted-foreground">·</span>
                    <span>{searchResult.unavailable_blocks} unavailable</span>
                  </>
                )}
                {searchResult.partial && (
                  <Badge variant="outline" className="border-amber-500 text-amber-700">
                    Partial
                  </Badge>
                )}
                {searchResult.truncated && (
                  <Badge variant="outline" className="border-slate-500 text-slate-700">
                    Truncated
                  </Badge>
                )}
                {searchResult.timed_out && (
                  <Badge variant="outline" className="border-red-500 text-red-700">
                    Timed out
                  </Badge>
                )}
              </div>

              {searchResult.returned_count === 0 ? (
                <p className="rounded-md border border-dashed border-border p-3 text-xs text-muted-foreground">
                  No results yet. The batch may still be buffered. Wait a couple of seconds and
                  click <em>Run example search</em> again.
                </p>
              ) : (
                <div className="space-y-1.5">
                  {searchResult.results.map((hit, i) => (
                    <div
                      key={i}
                      className="rounded border border-border/60 bg-background p-2.5 text-xs"
                    >
                      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                        <code className="font-mono text-[11px] text-muted-foreground">
                          {hit.timestamp}
                        </code>
                        <div className="flex flex-wrap gap-1">
                          {Object.entries(hit.tags).map(([k, v]) => (
                            <TagChip key={k} k={k} v={v} />
                          ))}
                        </div>
                      </div>
                      <div className="mt-1 font-mono text-[12px] text-foreground">
                        {hit.message}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <div className="text-[11px] text-muted-foreground">
                See <Link href="/help" className="underline">Help</Link> for what every field
                in the response strip above means. Visit{" "}
                <Link href="/status" className="underline">Status</Link> to watch the counters
                move.
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* STEP 3 */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Step 3. Try natural language</CardTitle>
          <CardDescription>
            The search page also has a Natural Language tab. Typing a plain-English query
            would produce the same shape of request as step 2. This deployment ships the UI
            and the translation logic but has no API key configured, so the tab explains that
            and defers to the structured form.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="rounded-lg border border-border bg-muted/30 p-3 font-mono text-xs">
            &quot;show me {DEMO_TENANT} payment errors from the last 10 minutes&quot;
          </div>
          <p className="text-xs text-muted-foreground">
            With a translation service configured, that sentence would generate this JSON,
            shown in an editable preview before it runs:
          </p>
          <JsonBlock label="Generated body (preview)" value={nlPreview} />
          <div className="flex flex-wrap items-center gap-3 pt-2">
            <Button asChild variant="outline">
              <Link href="/">
                Go to the search page
                <ArrowRight className="size-4" />
              </Link>
            </Button>
            <Link href="/help" className="text-xs underline">
              How natural-language search fails safely
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
