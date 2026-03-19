import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Webhook routes need raw body for signature verification
  experimental: {
    serverActions: {
      bodySizeLimit: '1mb',
    },
  },
};

export default nextConfig;
