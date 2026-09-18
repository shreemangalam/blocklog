import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm leading-relaxed text-muted-foreground">
        {children}
      </CardContent>
    </Card>
  );
}

function StatusRow({
  code,
  label,
  meaning,
  action,
}: {
  code: string;
  label: string;
  meaning: string;
  action: string;
}) {
  return (
    <div className="grid grid-cols-[64px_120px_1fr] items-start gap-3 border-t border-border py-2 first:border-t-0 first:pt-0">
      <code className="text-foreground">{code}</code>
      <div className="text-xs font-medium text-foreground">{label}</div>
      <div className="text-xs">
        <div>{meaning}</div>
        <div className="text-muted-foreground/80">Action: {action}</div>
      </div>
    </div>
  );
}

export default function HelpPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Help</h1>
        <p className="text-sm text-muted-foreground">
          What BlockLog does, how search works, and what its limits are.
        </p>
      </div>

      <Section title="At a glance">
        <ul className="list-disc space-y-2 pl-5">
          <li>
            <strong className="text-foreground">Index-free.</strong> No inverted index; a small
            in-memory catalog prunes candidates before any byte on disk is read.
          </li>
          <li>
            <strong className="text-foreground">Immutable blocks.</strong> Records land in
            LZ4-compressed files with CRC32 on both header and payload.
          </li>
          <li>
            <strong className="text-foreground">Honest partiality.</strong> Every response tells
            you exactly what it saw: candidate blocks, scanned blocks, unavailable blocks, and
            whether it hit its deadline.
          </li>
          <li>
            <strong className="text-foreground">Bounded time.</strong> Deadlines are enforced in
            nanoseconds. Queries never block indefinitely.
          </li>
        </ul>
      </Section>

      <Section title="Search semantics">
        <p>
          Every search requires a <code>tenant_id</code> and a <code>[from, to)</code> UTC time
          range. Tags are exact, case-sensitive key/value pairs combined with AND. Text is a
          case-sensitive literal substring match against one decoded message. There is no regex,
          no ranking, and no fuzzy matching.
        </p>
        <p>
          Results are the earliest matches by <code>(timestamp, block, record)</code> order, up
          to your result limit. If more matches exist than the limit, the response is marked{" "}
          <Badge variant="outline">Truncated</Badge>.
        </p>

        <div className="rounded-md border border-border bg-muted/40 p-3 text-xs">
          <div className="mb-1 font-medium text-foreground">Example request</div>
          <pre className="overflow-x-auto whitespace-pre-wrap font-mono">
{`POST /api/v1/search
{
  "tenant_id": "demo-shop",
  "from": "2026-09-18T00:00:00Z",
  "to":   "2026-09-19T00:00:00Z",
  "tags": { "env": "prod", "service": "checkout" },
  "text": "payment",
  "limit": 100,
  "timeout_ms": 5000
}`}
          </pre>
        </div>
      </Section>

      <Section title="Reading a response">
        <p>Every search response carries these completeness fields, and a good client shows them all:</p>
        <ul className="list-disc space-y-1 pl-5 text-xs">
          <li><code>candidate_blocks</code>: how many blocks survived tenant / time / tag pruning</li>
          <li><code>scanned_blocks</code>: how many were actually read to completion</li>
          <li><code>skipped_blocks</code>: how many failed mid-scan (corruption or IO)</li>
          <li><code>unavailable_blocks</code>: how many were already known bad at the query start</li>
          <li>
            <Badge variant="outline">Partial</Badge>: true when any of the above imply the
            result set is not the full answer
          </li>
          <li>
            <Badge variant="outline">Truncated</Badge>: true when more matches existed than the
            limit
          </li>
          <li><code>timed_out</code>: true when the query hit its deadline before finishing</li>
          <li><code>elapsed_ms</code>: engine time, not client round trip</li>
        </ul>
      </Section>

      <Section title="Two ways to search">
        <p>
          <strong className="text-foreground">Structured form</strong> is the primary interface.
          Fill in tenant, time range, tags, and text directly. It is always available and never
          depends on an external service.
        </p>
        <p>
          <strong className="text-foreground">Natural language</strong> is an additive, opt-in
          mode that asks a language model to translate a plain-English sentence into the same
          structured query. You always see and can edit the generated query before it runs; it
          calls the identical search endpoint and renders identical results. If no translation
          service is configured, this tab explains that and points back to the structured form.
          The project works completely without it.
        </p>
      </Section>

      <Section title="Eventual visibility, not instant">
        <p>
          Ingested records are acknowledged immediately with{" "}
          <code>202 buffered</code> but only become searchable after their block is flushed and
          published. Publication is triggered by either a 5,000,000-byte buffer or a 5-second age
          limit, whichever comes first. Search only sees a snapshot of already-published blocks
          at the moment the query starts.
        </p>
        <p className="text-xs">
          Practical impact: an event that happened 2 seconds ago may not yet be searchable.
          Widen your time range or wait a few seconds, then re-search.
        </p>
      </Section>

      <Section title="Partial results and damaged data">
        <p>
          A block that fails checksum validation is marked{" "}
          <Badge variant="outline">unavailable</Badge> rather than silently excluded. Any query
          whose candidate set intersects a skipped or unavailable block, or that hits its deadline
          before finishing, is marked <Badge variant="outline">Partial</Badge>. A partial result
          is never presented as a complete zero-match answer. The status view always reports how
          many blocks were scanned versus how many were candidates.
        </p>
        <p className="text-xs">
          You can demonstrate this on your own machine: seed a tenant, flip a byte in one of the{" "}
          <code>.blk</code> files under <code>backend/data/</code>, restart the backend, and
          re-run the same search. The response will carry <code>partial: true</code> and{" "}
          <code>unavailable_blocks &gt;= 1</code>; results from healthy blocks still come back.
        </p>
      </Section>

      <Section title="HTTP status reference">
        <StatusRow
          code="202"
          label="Buffered"
          meaning="Batch accepted into memory. Not yet durable."
          action="Continue. Records become searchable within the flush interval."
        />
        <StatusRow
          code="400"
          label="Bad request"
          meaning="Invalid fields: bad timestamp, missing tenant, from >= to."
          action="Fix the request body and retry."
        />
        <StatusRow
          code="413"
          label="Payload too large"
          meaning="Request exceeds a configured size guard."
          action="Split the batch into smaller chunks."
        />
        <StatusRow
          code="429"
          label="Overloaded"
          meaning="Queue or per-tenant budget exhausted. Error body names the tenant and cap."
          action="Back off and retry; consider raising per-tenant budget."
        />
        <StatusRow
          code="503"
          label="Unavailable"
          meaning="Persistence is unhealthy or the engine has not finished recovery."
          action="Check backend logs; wait and retry after health returns."
        />
      </Section>

      <Separator />

      <Section title="Durability limits, stated plainly">
        <p>
          There is no write-ahead log in this sprint. A buffered <code>202</code> means in-memory
          acceptance, not durability. A crash between acceptance and the next block publish can
          lose queued or unflushed records, potentially several seconds of traffic under backlog.
        </p>
        <p>
          Retries can create duplicates; deduplication is out of scope. This is a local,
          single-writer, trusted-tenant prototype. Not a production certification. A production
          deployment would need a WAL, an authentication boundary in front of{" "}
          <code>tenant_id</code>, and per-tenant retention plus quotas.
        </p>
      </Section>

      <Section title="Where to look next">
        <ul className="list-disc space-y-1 pl-5 text-xs">
          <li>
            <a href="/status" className="text-primary underline underline-offset-2">Status</a>{" "}
            shows live counters, buffer pressure, and unavailable blocks.
          </li>
          <li>
            <a href="/onboarding" className="text-primary underline underline-offset-2">Onboarding</a>{" "}
            walks through a three-step ingest and search flow against the real backend.
          </li>
          <li>
            <a href="/" className="text-primary underline underline-offset-2">Search</a> is the
            structured query surface, with a Natural Language tab for translated queries.
          </li>
          <li>
            <code>/actuator/prometheus</code> on the backend exposes 20 plus counters, gauges,
            and timers for ops.
          </li>
        </ul>
      </Section>
    </div>
  );
}
