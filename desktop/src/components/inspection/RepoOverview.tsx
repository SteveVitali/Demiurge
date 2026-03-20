import type { RepoInspectionReport } from '@/api/types';
import { ConfidenceBar } from '@/components/shared/ConfidenceBar';
import { ServiceKindIcon } from '@/components/shared/ServiceKindIcon';
import { formatDateTime } from '@/lib/utils';

interface RepoOverviewProps {
  report: RepoInspectionReport;
}

export function RepoOverview({ report }: RepoOverviewProps) {
  return (
    <div className="space-y-4">
      {/* Repo info */}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-1 text-sm">
        <div>
          <span className="text-muted-foreground">Repo: </span>
          <span className="font-mono text-foreground">{report.repoPath}</span>
        </div>
        {report.gitRef && (
          <div>
            <span className="text-muted-foreground">Ref: </span>
            <span className="font-mono text-foreground">{report.gitRef}</span>
          </div>
        )}
        <div>
          <span className="text-muted-foreground">Inspected: </span>
          <span className="text-foreground">{formatDateTime(report.inspectedAt)}</span>
        </div>
      </div>

      {/* Languages & Frameworks */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {/* Languages */}
        <div className="rounded-lg border border-border p-3">
          <h4 className="text-xs font-medium text-muted-foreground mb-2">Languages</h4>
          <div className="space-y-1.5">
            {report.languages.map((lang) => (
              <div key={lang.value} className="flex items-center justify-between gap-2">
                <span className="text-sm text-foreground">{lang.value}</span>
                <ConfidenceBar value={lang.confidence} />
              </div>
            ))}
            {report.languages.length === 0 && (
              <span className="text-xs text-muted-foreground">No languages detected</span>
            )}
          </div>
        </div>

        {/* Frameworks */}
        <div className="rounded-lg border border-border p-3">
          <h4 className="text-xs font-medium text-muted-foreground mb-2">Frameworks</h4>
          <div className="space-y-1.5">
            {report.frameworks.map((fw) => (
              <div key={fw.value} className="flex items-center justify-between gap-2">
                <span className="text-sm text-foreground">{fw.value}</span>
                <ConfidenceBar value={fw.confidence} />
              </div>
            ))}
            {report.frameworks.length === 0 && (
              <span className="text-xs text-muted-foreground">No frameworks detected</span>
            )}
          </div>
        </div>
      </div>

      {/* Candidate Services */}
      {report.candidateServices.length > 0 && (
        <div className="rounded-lg border border-border p-3">
          <h4 className="text-xs font-medium text-muted-foreground mb-2">Candidate Services</h4>
          <div className="space-y-2">
            {report.candidateServices.map((svc) => (
              <div key={svc.serviceId} className="flex items-center gap-3 text-sm">
                <ServiceKindIcon kind={svc.kind} size={14} />
                <span className="font-medium text-foreground">{svc.serviceId}</span>
                <span className="text-xs text-muted-foreground">({svc.kind})</span>
                <ConfidenceBar value={svc.confidence} className="ml-auto" />
                {svc.port && (
                  <span className="text-xs text-muted-foreground">:{svc.port}</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Manifests Found */}
      {report.manifestsFound.length > 0 && (
        <div className="rounded-lg border border-border p-3">
          <h4 className="text-xs font-medium text-muted-foreground mb-2">Manifests Found</h4>
          <div className="flex flex-wrap gap-2">
            {report.manifestsFound.map((m) => (
              <span
                key={m.relativePath}
                className={`inline-flex items-center rounded px-2 py-0.5 text-xs ${
                  m.parsedSuccessfully
                    ? 'bg-emerald-500/10 text-emerald-400'
                    : 'bg-red-500/10 text-red-400'
                }`}
                title={m.parsedSuccessfully ? m.manifestType : `${m.manifestType} — ${m.parseErrors.join(', ')}`}
              >
                {m.relativePath}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
