"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { X } from "lucide-react";

interface TagFilterInputProps {
  tags: Record<string, string>;
  onChange: (tags: Record<string, string>) => void;
}

export function TagFilterInput({ tags, onChange }: TagFilterInputProps) {
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");

  function addTag() {
    const k = key.trim();
    const v = value.trim();
    if (!k || !v) return;
    onChange({ ...tags, [k]: v });
    setKey("");
    setValue("");
  }

  function removeTag(k: string) {
    const next = { ...tags };
    delete next[k];
    onChange(next);
  }

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <Input
          placeholder="key (e.g. env)"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTag())}
          className="flex-1"
        />
        <Input
          placeholder="value (e.g. prod)"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTag())}
          className="flex-1"
        />
        <Button type="button" variant="outline" onClick={addTag}>
          Add
        </Button>
      </div>
      {Object.keys(tags).length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {Object.entries(tags).map(([k, v]) => (
            <Badge key={k} variant="secondary" className="gap-1 pr-1">
              {k}={v}
              <button
                type="button"
                onClick={() => removeTag(k)}
                className="ml-0.5 rounded-full p-0.5 hover:bg-foreground/10"
                aria-label={`Remove tag ${k}`}
              >
                <X className="size-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}
    </div>
  );
}
