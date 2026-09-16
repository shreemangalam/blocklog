"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { TagFilterInput } from "./tag-filter-input";
import type { SearchRequest } from "@/lib/types";

interface StructuredSearchFormProps {
  onSubmit: (request: SearchRequest) => void;
  loading: boolean;
  initial?: Partial<SearchRequest>;
}

function toDatetimeLocal(iso?: string): string {
  if (!iso) return "";
  try {
    return new Date(iso).toISOString().slice(0, 16);
  } catch {
    return "";
  }
}

function defaultTimeRange() {
  const to = new Date();
  const from = new Date(to.getTime() - 60 * 60 * 1000);
  return {
    from: from.toISOString().slice(0, 16),
    to: to.toISOString().slice(0, 16),
  };
}

export function StructuredSearchForm({ onSubmit, loading, initial }: StructuredSearchFormProps) {
  const defaults = defaultTimeRange();
  const [tenantId, setTenantId] = useState(initial?.tenant_id ?? "");
  const [from, setFrom] = useState(toDatetimeLocal(initial?.from) || defaults.from);
  const [to, setTo] = useState(toDatetimeLocal(initial?.to) || defaults.to);
  const [tags, setTags] = useState<Record<string, string>>(initial?.tags ?? {});
  const [text, setText] = useState(initial?.text ?? "");
  const [limit, setLimit] = useState(initial?.limit ?? 100);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!tenantId.trim() || !from || !to) return;

    // The datetime-local input yields a naive "YYYY-MM-DDTHH:MM" string.
    // Passing that to new Date() parses it as local time, which contradicts
    // the "(UTC)" label. Appending Z forces UTC interpretation so the
    // number the user typed is the UTC instant we search against.
    onSubmit({
      tenant_id: tenantId.trim(),
      from: new Date(from + "Z").toISOString(),
      to: new Date(to + "Z").toISOString(),
      tags: Object.keys(tags).length > 0 ? tags : undefined,
      text: text.trim() || undefined,
      limit,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="tenant_id">Tenant ID</Label>
          <Input
            id="tenant_id"
            placeholder="acme"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            required
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="limit">Result limit</Label>
          <Input
            id="limit"
            type="number"
            min={1}
            max={1000}
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value))}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="from">From (UTC)</Label>
          <Input
            id="from"
            type="datetime-local"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            required
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="to">To (UTC)</Label>
          <Input
            id="to"
            type="datetime-local"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            required
          />
        </div>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="text">Text contains (literal, case-sensitive)</Label>
        <Input
          id="text"
          placeholder="payment failed"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      </div>

      <div className="space-y-1.5">
        <Label>Tags (exact match, AND)</Label>
        <TagFilterInput tags={tags} onChange={setTags} />
      </div>

      <Button type="submit" disabled={loading} className="w-full sm:w-auto">
        {loading ? "Searching…" : "Search"}
      </Button>
    </form>
  );
}
