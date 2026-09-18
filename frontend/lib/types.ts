export interface SearchRequest {
  tenant_id: string;
  from: string;
  to: string;
  tags?: Record<string, string>;
  text?: string;
  limit?: number;
  timeout_ms?: number;
}

export interface SearchHit {
  timestamp: string;
  tags: Record<string, string>;
  message: string;
}

export interface SearchResponse {
  results: SearchHit[];
  returned_count: number;
  partial: boolean;
  truncated: boolean;
  timed_out: boolean;
  skipped_blocks: number;
  unavailable_blocks: number;
  candidate_blocks: number;
  scanned_blocks: number;
  elapsed_ms: number;
}

export interface StatusResponse {
  status: string;
  accepted_records: number;
  persisted_records: number;
  accepted_bytes: number;
  persisted_bytes: number;
  published_blocks: number;
  unavailable_blocks: number;
  active_queries: number;
  buffer_usage_pct: number;
  persistence_healthy: boolean;
}

export interface IngestRecordInput {
  timestamp: string;
  tags?: Record<string, string>;
  message: string;
}

export interface IngestRequest {
  tenant_id: string;
  records: IngestRecordInput[];
}

export interface IngestResponse {
  accepted_records: number;
  durability: string;
}

export interface ApiErrorBody {
  error?: string;
  errors?: string[];
}
