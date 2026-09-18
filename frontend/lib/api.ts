import type {
  ApiErrorBody,
  IngestRequest,
  IngestResponse,
  SearchRequest,
  SearchResponse,
  StatusResponse,
} from "./types";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  status: number;
  body: ApiErrorBody | null;

  constructor(status: number, body: ApiErrorBody | null, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiError(0, null, "Could not reach the BlockLog API. Is the backend running?");
  }

  if (!response.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = await response.json();
    } catch {
      // response had no JSON body
    }
    const message =
      body?.error ??
      (body?.errors && body.errors.join("; ")) ??
      `Request failed with status ${response.status}`;
    throw new ApiError(response.status, body, message);
  }

  return response.json() as Promise<T>;
}

export function searchLogs(req: SearchRequest): Promise<SearchResponse> {
  return request<SearchResponse>("/api/v1/search", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function ingestLogs(req: IngestRequest): Promise<IngestResponse> {
  return request<IngestResponse>("/api/v1/logs", {
    method: "POST",
    body: JSON.stringify(req),
  });
}

export function getStatus(): Promise<StatusResponse> {
  return request<StatusResponse>("/api/v1/status", { method: "GET" });
}

export { API_BASE_URL };
