const GITHUB_REPO = process.env.NEXT_PUBLIC_GITHUB_REPO ?? 'SteveVitali/Demiurge';

export interface ReleaseAsset {
  name: string;
  url: string;
  size: number;
}

export interface LatestRelease {
  version: string;
  publishedAt: string;
  assets: ReleaseAsset[];
}

export async function getLatestRelease(): Promise<LatestRelease | null> {
  try {
    const headers: Record<string, string> = {
      Accept: 'application/vnd.github.v3+json',
    };

    if (process.env.GITHUB_TOKEN) {
      headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
    }

    const res = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`,
      {
        headers,
        next: { revalidate: 3600 },
      },
    );

    if (!res.ok) return null;

    const release = await res.json();

    return {
      version: release.tag_name,
      publishedAt: release.published_at,
      assets: (release.assets ?? []).map((a: Record<string, unknown>) => ({
        name: a.name as string,
        url: a.browser_download_url as string,
        size: a.size as number,
      })),
    };
  } catch {
    return null;
  }
}

export function findAsset(
  assets: ReleaseAsset[],
  pattern: RegExp,
): ReleaseAsset | undefined {
  return assets.find((a) => pattern.test(a.name));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  return `${mb.toFixed(1)} MB`;
}
