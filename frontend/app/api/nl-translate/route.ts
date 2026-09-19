import { NextRequest, NextResponse } from "next/server";

const LLM_MODEL = process.env.LLM_MODEL ?? "claude-sonnet-4-6";
const NL_TIMEOUT_MS = 8000;
const KNOWN_TENANTS = ["acme", "globex", "initech"];

function buildSystemPrompt(nowIso: string): string {
  return [
    "You translate a natural-language incident-response query into a JSON object matching this exact schema for POST /api/v1/search:",
    "{",
    '  "tenant_id": string (required),',
    '  "from": ISO-8601 UTC timestamp (required),',
    '  "to": ISO-8601 UTC timestamp (required, must be after from),',
    '  "tags": object of exact string key/value pairs (optional),',
    '  "text": string literal substring, case-sensitive (optional),',
    '  "limit": integer 1-1000 (optional, default 100),',
    '  "timeout_ms": integer 1-30000 (optional, default 5000)',
    "}",
    `The current UTC time is ${nowIso}. Resolve relative time expressions ("last 2 hours", "yesterday") against this instant.`,
    `Known tenant identifiers: ${KNOWN_TENANTS.join(", ")}. Match informal references to the closest known tenant.`,
    "Respond with valid JSON only. No markdown fencing, no explanation, no extra keys.",
  ].join("\n");
}

export async function POST(request: NextRequest) {
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    return NextResponse.json(
      { error: "Natural-language search is not configured on this server." },
      { status: 503 }
    );
  }

  let body: { query?: string };
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const query = body.query;
  if (!query || typeof query !== "string" || !query.trim()) {
    return NextResponse.json({ error: "query is required" }, { status: 400 });
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), NL_TIMEOUT_MS);

  try {
    const nowIso = new Date().toISOString();
    const response = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: LLM_MODEL,
        max_tokens: 512,
        system: buildSystemPrompt(nowIso),
        messages: [{ role: "user", content: query.trim() }],
      }),
    });

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      return NextResponse.json(
        { error: `Translation service returned ${response.status}`, detail: text },
        { status: 502 }
      );
    }

    const data = await response.json();
    const text: string = data?.content?.[0]?.text ?? "";

    return NextResponse.json({ text });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      return NextResponse.json(
        { error: "Translation timed out" },
        { status: 504 }
      );
    }
    return NextResponse.json(
      { error: "Could not reach translation service" },
      { status: 502 }
    );
  } finally {
    clearTimeout(timer);
  }
}

export async function GET() {
  const configured = Boolean(process.env.ANTHROPIC_API_KEY);
  return NextResponse.json({ enabled: configured });
}
