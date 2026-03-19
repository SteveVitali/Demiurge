import type { Metadata } from 'next';
import { getLatestRelease } from '@/lib/github-releases';
import { DownloadCards } from '@/components/download/DownloadCards';
import { InstallInstructions } from '@/components/download/InstallInstructions';

export const metadata: Metadata = {
  title: 'Download',
  description:
    'Download Demiurge for macOS, Windows, or Linux. Also available via Homebrew and direct install.',
};

export const revalidate = 3600;

export default async function DownloadPage() {
  const release = await getLatestRelease();

  return (
    <div className="py-24">
      <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8">
        <div className="text-center mb-16">
          <h1 className="text-4xl sm:text-5xl font-bold text-text-primary">
            Download Demiurge
          </h1>
          <p className="mt-4 text-lg text-text-secondary max-w-2xl mx-auto">
            Get the desktop app or CLI for your platform.
            {release?.version && (
              <span className="ml-2 text-text-muted">
                Latest: {release.version}
              </span>
            )}
          </p>
        </div>

        <DownloadCards assets={release?.assets ?? []} />

        <div className="mt-12">
          <InstallInstructions />
        </div>
      </div>
    </div>
  );
}
