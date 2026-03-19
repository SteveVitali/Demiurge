import { useState } from 'react';
import { ZoomIn, ZoomOut, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { API_BASE_URL } from '@/lib/constants';

interface ScreenshotGalleryProps {
  runId: string;
  artifactId: string;
  contentType: string;
  className?: string;
}

export function ScreenshotGallery({ runId, artifactId, className }: ScreenshotGalleryProps) {
  const [zoom, setZoom] = useState(1);
  const [lightbox, setLightbox] = useState(false);
  const src = `${API_BASE_URL}/runs/${runId}/artifacts/${artifactId}/content`;

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
      </div>

      <div className="overflow-auto flex-1">
        <img
          src={src}
          alt="Screenshot"
          className="cursor-pointer transition-transform"
          style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
          onClick={() => setLightbox(true)}
        />
      </div>

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
