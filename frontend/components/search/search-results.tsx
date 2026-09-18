import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { AlertTriangle, Clock, Inbox } from "lucide-react";
import type { SearchResponse } from "@/lib/types";

interface SearchResultsProps {
  loading: boolean;
  error: string | null;
  response: SearchResponse | null;
}

export function SearchResults({ loading, error, response }: SearchResultsProps) {
  if (loading) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertTriangle className="size-4" />
        <AlertTitle>Search failed</AlertTitle>
        <AlertDescription>{error}</AlertDescription>
      </Alert>
    );
  }

  if (!response) {
    return null;
  }

  if (response.returned_count === 0) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border py-12 text-center text-muted-foreground">
        <Inbox className="size-8" />
        <p className="text-sm">No matching records for this query.</p>
        {response.candidate_blocks === 0 && (
          <p className="text-xs">No blocks matched this tenant/time range. Check tenant ID and range.</p>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock className="size-3.5" />
          {response.elapsed_ms}ms
        </span>
        <span>·</span>
        <span>
          {response.scanned_blocks}/{response.candidate_blocks} blocks scanned
        </span>
        {response.partial && (
          <Badge variant="outline" className="border-amber-500 text-amber-700">
            Partial results
          </Badge>
        )}
        {response.timed_out && (
          <Badge variant="outline" className="border-amber-500 text-amber-700">
            Timed out
          </Badge>
        )}
        {response.truncated && (
          <Badge variant="outline" className="border-blue-500 text-blue-700">
            Truncated at limit
          </Badge>
        )}
        {response.skipped_blocks > 0 && (
          <Badge variant="outline" className="border-destructive text-destructive">
            {response.skipped_blocks} block(s) skipped
          </Badge>
        )}
        {response.unavailable_blocks > 0 && (
          <Badge variant="outline" className="border-destructive text-destructive">
            {response.unavailable_blocks} block(s) unavailable
          </Badge>
        )}
      </div>

      <div className="overflow-x-auto rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-48">Timestamp</TableHead>
              <TableHead className="w-56">Tags</TableHead>
              <TableHead>Message</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {response.results.map((hit, i) => (
              <TableRow key={i}>
                <TableCell className="font-mono text-xs whitespace-nowrap align-top">
                  {hit.timestamp}
                </TableCell>
                <TableCell className="align-top">
                  <div className="flex flex-wrap gap-1">
                    {Object.entries(hit.tags).map(([k, v]) => (
                      <Badge key={k} variant="secondary" className="font-mono text-[0.65rem]">
                        {k}={v}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
                <TableCell className="whitespace-pre-wrap align-top font-mono text-xs">
                  {hit.message}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
