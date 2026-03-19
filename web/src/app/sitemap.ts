import type { MetadataRoute } from 'next';
import { getAllDocs } from '@/lib/docs';

export default function sitemap(): MetadataRoute.Sitemap {
  const baseUrl = 'https://demiurge.dev';

  const staticPages: MetadataRoute.Sitemap = [
    { url: baseUrl, lastModified: new Date(), changeFrequency: 'weekly', priority: 1.0 },
    { url: `${baseUrl}/pricing`, lastModified: new Date(), changeFrequency: 'monthly', priority: 0.9 },
    { url: `${baseUrl}/download`, lastModified: new Date(), changeFrequency: 'weekly', priority: 0.9 },
    { url: `${baseUrl}/docs`, lastModified: new Date(), changeFrequency: 'weekly', priority: 0.8 },
    { url: `${baseUrl}/blog`, lastModified: new Date(), changeFrequency: 'weekly', priority: 0.5 },
    { url: `${baseUrl}/legal/terms`, lastModified: new Date(), changeFrequency: 'yearly', priority: 0.3 },
    { url: `${baseUrl}/legal/privacy`, lastModified: new Date(), changeFrequency: 'yearly', priority: 0.3 },
  ];

  let docPages: MetadataRoute.Sitemap = [];
  try {
    const docs = getAllDocs();
    docPages = docs.map((doc) => ({
      url: `${baseUrl}/docs/${doc.slug}`,
      lastModified: new Date(),
      changeFrequency: 'weekly' as const,
      priority: 0.7,
    }));
  } catch {
    // Docs may not be available during build in all environments
  }

  return [...staticPages, ...docPages];
}
