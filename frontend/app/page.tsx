"use client";

import { useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { StructuredSearchForm } from "@/components/search/structured-search-form";
import { NLSearchTab } from "@/components/search/nl-search-tab";
import { SearchResults } from "@/components/search/search-results";
import { searchLogs, ApiError } from "@/lib/api";
import type { SearchRequest, SearchResponse } from "@/lib/types";

export default function SearchPage() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [hasSearched, setHasSearched] = useState(false);

  async function runSearch(request: SearchRequest) {
    setLoading(true);
    setError(null);
    setHasSearched(true);
    try {
      const result = await searchLogs(request);
      setResponse(result);
    } catch (err) {
      setResponse(null);
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Unexpected error while searching.");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Search logs</h1>
        <p className="text-sm text-muted-foreground">
          Query persisted, published blocks by tenant, time range, tags, and literal text.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Query</CardTitle>
          <CardDescription>
            The structured form is the primary interface. Natural-language mode is an
            additive convenience layer over the same search API.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="structured">
            <TabsList>
              <TabsTrigger value="structured">Structured</TabsTrigger>
              <TabsTrigger value="nl">Natural Language</TabsTrigger>
            </TabsList>
            <TabsContent value="structured" className="pt-4">
              <StructuredSearchForm onSubmit={runSearch} loading={loading} />
            </TabsContent>
            <TabsContent value="nl" className="pt-4">
              <NLSearchTab onExecute={runSearch} loading={loading} />
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>

      {hasSearched && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Results</CardTitle>
          </CardHeader>
          <CardContent>
            <SearchResults loading={loading} error={error} response={response} />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
