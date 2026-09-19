"use client";

import { useEffect, useState, useCallback } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { getStatus, ApiError } from "@/lib/api";
import type { StatusResponse } from "@/lib/types";

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg border border-border p-4">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-xl font-semibold tabular-nums">{value}</p>
    </div>
  );
}

export default function StatusPage() {
  const [status, setStatus] = useState<StatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchData = useCallback(async () => {
    try {
      const result = await getStatus();
      setStatus(result);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load status.");
    } finally {
      setLoading(false);
    }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    await fetchData();
  }, [fetchData]);

  useEffect(() => {
    // All setState calls inside fetchData happen after `await getStatus()` — not synchronous.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void fetchData();
    const interval = setInterval(() => void fetchData(), 5000);
    return () => clearInterval(interval);
  }, [fetchData]);

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-8 sm:px-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Engine status</h1>
          <p className="text-sm text-muted-foreground">
            Live counters, buffer pressure, and recovery health. Refreshes every 5s.
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={refresh} disabled={loading}>
          <RefreshCw className={loading ? "size-4 animate-spin" : "size-4"} />
          Refresh
        </Button>
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertTitle>Could not reach BlockLog API</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {status && (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                Persistence
                <Badge variant={status.persistence_healthy ? "secondary" : "destructive"}>
                  {status.status}
                </Badge>
              </CardTitle>
              <CardDescription>
                Accepted counters increase on buffered ingestion (HTTP 202); persisted counters
                increase only after a block is durably published.
              </CardDescription>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Metric label="Accepted records" value={status.accepted_records} />
              <Metric label="Persisted records" value={status.persisted_records} />
              <Metric label="Accepted bytes" value={formatBytes(status.accepted_bytes)} />
              <Metric label="Persisted bytes" value={formatBytes(status.persisted_bytes)} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Catalog and queries</CardTitle>
              <CardDescription>
                Blocks discovered on disk (including recovered files) and active search load.
              </CardDescription>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Metric label="Published blocks" value={status.published_blocks} />
              <Metric label="Unavailable blocks" value={status.unavailable_blocks} />
              <Metric label="Active queries" value={status.active_queries} />
              <Metric label="Buffer usage" value={`${status.buffer_usage_pct.toFixed(1)}%`} />
            </CardContent>
          </Card>

          {status.unavailable_blocks > 0 && (
            <Alert>
              <AlertTriangle className="size-4" />
              <AlertTitle>{status.unavailable_blocks} block(s) unavailable</AlertTitle>
              <AlertDescription>
                These files failed header validation during discovery (corruption or a crash
                mid-write). They remain excluded from search and are reported as reduced
                completeness on affected queries, not silently dropped.
              </AlertDescription>
            </Alert>
          )}
        </>
      )}
    </div>
  );
}
