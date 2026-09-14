import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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

export default function HelpPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-8 sm:px-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Help</h1>
        <p className="text-sm text-muted-foreground">
          What BlockLog does, how search works, and what its limits are.
        </p>
      </div>

      <Section title="Search semantics">
        <p>
          Every search requires a <code>tenant_id</code> and a <code>[from, to)</code> UTC time
          range. Tags are exact, case-sensitive key/value pairs combined with AND. Text is a
          case-sensitive literal substring match against one decoded message — there is no
          regex, ranking, or fuzzy matching.
        </p>
        <p>
          Results are the earliest matches by (timestamp, block, record) order, up to your
          result limit. If more matches exist than the limit, the response is marked{" "}
          <Badge variant="outline">Truncated</Badge>.
        </p>
      </Section>

      <Section title="Two ways to search">
        <p>
          <strong className="text-foreground">Structured form</strong> — the primary interface.
          Fill in tenant, time range, tags, and text directly. It is always available and never
          depends on an external service.
        </p>
        <p>
          <strong className="text-foreground">Natural language</strong> — an additive, opt-in
          mode that asks a language model to translate a plain-English sentence into the same
          structured query. You always see and can edit the generated query before it runs; it
          calls the identical search endpoint and renders identical results. If no translation
          service is configured, this tab explains that and points back to the structured form
          — the project works completely without it.
        </p>
      </Section>

      <Section title="Eventual visibility, not instant">
        <p>
          Ingested records are acknowledged immediately (<code>202 buffered</code>) but only
          become searchable after their block is flushed and published — triggered by either a
          5,000,000-byte buffer or a 5-second age limit, whichever comes first. Search only sees
          a snapshot of already-published blocks at the moment the query starts.
        </p>
      </Section>

      <Section title="Partial results and damaged data">
        <p>
          A block that fails checksum validation is marked <Badge variant="outline">unavailable</Badge>{" "}
          rather than silently excluded. Any query whose candidate set intersects a
          skipped/unavailable block, or that hits its deadline before finishing, is marked{" "}
          <Badge variant="outline">Partial</Badge>. A partial result is never presented as a
          complete zero-match answer — the status view always reports how many blocks were
          scanned versus how many were candidates.
        </p>
      </Section>

      <Section title="Overload and failure responses">
        <p>
          <code>400</code> — invalid fields (bad timestamp, missing tenant, <code>from ≥ to</code>).{" "}
          <code>413</code> — request exceeds a size guard. <code>429</code> — temporary admission
          pressure (buffers full); retry after backoff.{" "}
          <code>503</code> — persistence is unhealthy or the engine has not finished recovery.
        </p>
      </Section>

      <Separator />

      <Section title="Durability limits, stated plainly">
        <p>
          There is no write-ahead log in this sprint. A buffered <code>202</code> means
          in-memory acceptance, not durability — a crash can lose queued or unflushed records,
          potentially several seconds of traffic under backlog. Retries can create duplicates;
          deduplication is out of scope. This is a local, single-writer, trusted-tenant
          prototype, not a production certification.
        </p>
      </Section>
    </div>
  );
}
