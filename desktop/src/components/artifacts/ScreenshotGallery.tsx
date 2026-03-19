import { useState, useRef, useCallback } from 'react';
import { ZoomIn, ZoomOut, X, Columns2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { API_BASE_URL } from '@/lib/constants';

// Desktop Phase 5: Enhanced screenshot gallery with side-by-side comparison mode
// and slider overlay for visual diff between two screenshots.

interface ScreenshotGalleryProps {
  runId: string;
  artifactId: string;
  compareArtifactId?: string;
  className?: string;
}

type ViewMode = 'single' | 'side-by-side' | 'slider';

export function ScreenshotGallery({ runId, artifactId, compareArtifactId, className }: ScreenshotGalleryProps) {
  const [zoom, setZoom] = useState(1);
  const [lightbox, setLightbox] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('single');
  const [sliderPos, setSliderPos] = useState(50);
  const sliderRef = useRef<HTMLDivElement>(null);

  const src = `${API_BASE_URL}/runs/${runId}/artifacts/${artifactId}/content`;
  const compareSrc = compareArtifactId
    ? `${API_BASE_URL}/runs/${runId}/artifacts/${compareArtifactId}/content`
    : null;

  const handleSliderMove = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (!sliderRef.current) return;
    const rect = sliderRef.current.getBoundingClientRect();
    const x = Math.max(0, Math.min(e.clientX - rect.left, rect.width));
    setSliderPos((x / rect.width) * 100);
  }, []);

  const hasCompare = !!compareSrc;

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center gap-2 px-2">
        <button
          onClick={() => setZoom((z) => Math.max(0.25, z - 0.25))}
          className="rounded p-1 hover:bg-muted transition-colors"
          title="Zoom out"
        >
          <ZoomOut size={14} />
        </button>
        <span className="text-xs text-muted-foreground">{Math.round(zoom * 100)}%</span>
        <button
          onClick={() => setZoom((z) => Math.min(4, z + 0.25))}
          className="rounded p-1 hover:bg-muted transition-colors"
          title="Zoom in"
        >
          <ZoomIn size={14} />
        </button>

        {hasCompare && (
          <>
            <div className="mx-1 h-4 w-px bg-border" />
            <button
              onClick={() => setViewMode(viewMode === 'side-by-side' ? 'single' : 'side-by-side')}
              className={cn(
                'rounded p-1 transition-colors',
                viewMode === 'side-by-side' ? 'bg-blue-500/20 text-blue-400' : 'hover:bg-muted text-muted-foreground',
              )}
              title="Side-by-side comparison"
            >
              <Columns2 size={14} />
            </button>
            <button
              onClick={() => setViewMode(viewMode === 'slider' ? 'single' : 'slider')}
              className={cn(
                'rounded px-2 py-0.5 text-xs transition-colors',
                viewMode === 'slider' ? 'bg-blue-500/20 text-blue-400' : 'hover:bg-muted text-muted-foreground',
              )}
              title="Slider overlay comparison"
            >
              Slider
            </button>
          </>
        )}
      </div>

      {/* Single view */}
      {viewMode === 'single' && (
        <div className="overflow-auto flex-1">
          <img
            src={src}
            alt="Screenshot"
            className="cursor-pointer transition-transform"
            style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
            onClick={() => setLightbox(true)}
          />
        </div>
      )}

      {/* Side-by-side view */}
      {viewMode === 'side-by-side' && compareSrc && (
        <div className="flex gap-2 overflow-auto flex-1">
          <div className="flex-1 overflow-auto border border-border rounded">
            <div className="px-2 py-1 text-xs text-muted-foreground border-b border-border bg-muted/30">Before</div>
            <img
              src={compareSrc}
              alt="Before"
              className="transition-transform"
              style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
            />
          </div>
          <div className="flex-1 overflow-auto border border-border rounded">
            <div className="px-2 py-1 text-xs text-muted-foreground border-b border-border bg-muted/30">After</div>
            <img
              src={src}
              alt="After"
              className="transition-transform"
              style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
            />
          </div>
        </div>
      )}

      {/* Slider overlay view */}
      {viewMode === 'slider' && compareSrc && (
        <div
          ref={sliderRef}
          className="relative overflow-hidden flex-1 cursor-col-resize"
          onMouseMove={handleSliderMove}
        >
          {/* Base image (after) */}
          <img src={src} alt="After" className="w-full" />
          {/* Overlay image (before) clipped to slider position */}
          <div
            className="absolute inset-0 overflow-hidden"
            style={{ width: `${sliderPos}%` }}
          >
            <img src={compareSrc} alt="Before" className="w-full" style={{ minWidth: sliderRef.current?.offsetWidth }} />
          </div>
          {/* Slider line */}
          <div
            className="absolute top-0 bottom-0 w-0.5 bg-white shadow-lg"
            style={{ left: `${sliderPos}%` }}
          >
            <div className="absolute top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white w-4 h-4 shadow-md border border-zinc-300" />
          </div>
          {/* Labels */}
          <div className="absolute top-2 left-2 rounded bg-black/60 px-2 py-0.5 text-xs text-white">Before</div>
          <div className="absolute top-2 right-2 rounded bg-black/60 px-2 py-0.5 text-xs text-white">After</div>
        </div>
      )}

      {lightbox && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
          onClick={() => setLightbox(false)}
        >
          <button
            onClick={() => setLightbox(false)}
            className="absolute top-4 right-4 rounded p-2 text-white hover:bg-white/10"
          >
            <X size={20} />
          </button>
          <img
            src={src}
            alt="Screenshot (full)"
            className="max-h-[90vh] max-w-[90vw] object-contain"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  );
}
