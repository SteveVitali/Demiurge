import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ArtifactRecord, ArtifactType } from '@/api/types';
import { ArtifactTypeIcon } from '@/components/shared/ArtifactTypeIcon';

interface ArtifactTreeProps {
  artifacts: ArtifactRecord[];
  selectedId: string | null;
  onSelect: (artifact: ArtifactRecord) => void;
  className?: string;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

interface GroupedArtifacts {
  type: ArtifactType;
  items: ArtifactRecord[];
}

function groupByType(artifacts: ArtifactRecord[]): GroupedArtifacts[] {
  const map = new Map<ArtifactType, ArtifactRecord[]>();
  for (const a of artifacts) {
    const existing = map.get(a.artifactType) ?? [];
    existing.push(a);
    map.set(a.artifactType, existing);
  }
  return Array.from(map.entries()).map(([type, items]) => ({ type, items }));
}

export function ArtifactTree({ artifacts, selectedId, onSelect, className }: ArtifactTreeProps) {
  const groups = groupByType(artifacts);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const toggleGroup = (type: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(type)) next.delete(type);
      else next.add(type);
      return next;
    });
  };

  return (
    <div className={cn('overflow-y-auto', className)}>
      {groups.map((group) => (
        <div key={group.type}>
          <button
            onClick={() => toggleGroup(group.type)}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-xs font-medium text-muted-foreground hover:bg-muted/50 transition-colors"
          >
            {collapsed.has(group.type) ? (
              <ChevronRight size={12} />
            ) : (
              <ChevronDown size={12} />
            )}
            <ArtifactTypeIcon type={group.type} size={14} />
            <span className="truncate">{group.type}</span>
            <span className="ml-auto text-[10px] opacity-60">{group.items.length}</span>
          </button>

          {!collapsed.has(group.type) && (
            <div className="pl-4">
              {group.items.map((artifact) => {
                const fileName = artifact.relativePath.split('/').pop() ?? artifact.relativePath;
                const isSelected = artifact.artifactId === selectedId;

                return (
                  <button
                    key={artifact.artifactId}
                    onClick={() => onSelect(artifact)}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-md px-3 py-1 text-xs transition-colors',
                      isSelected
                        ? 'bg-blue-500/20 text-blue-400'
                        : 'text-muted-foreground hover:bg-muted/50 hover:text-foreground',
                    )}
                  >
                    <span className="truncate flex-1 text-left">{fileName}</span>
                    <span className="text-[10px] opacity-60 shrink-0">{formatSize(artifact.sizeBytes)}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
