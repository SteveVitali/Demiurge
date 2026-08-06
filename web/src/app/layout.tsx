import { ClerkProvider } from '@clerk/nextjs';
import { Inter } from 'next/font/google';
import type { Metadata } from 'next';
import { Navbar } from '@/components/layout/Navbar';
import { Footer } from '@/components/layout/Footer';
import './globals.css';

const inter = Inter({ subsets: ['latin'] });

export const metadata: Metadata = {
  title: {
    default: 'Demiurge — AI-Powered Web Development Automation',
    template: '%s | Demiurge',
  },
  description:
    'Automatically boot, verify, and repair your web applications with AI agents. Desktop app + CLI.',
  openGraph: {
    title: 'Demiurge',
    description: 'Ship verified software with AI agents',
    url: 'https://demiurge.dev',
    siteName: 'Demiurge',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Demiurge',
    description: 'Ship verified software with AI agents',
  },
  metadataBase: new URL('https://demiurge.dev'),
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <ClerkProvider>
      <html lang="en" className="dark">
        <body className={inter.className}>
          <Navbar />
          <main className="min-h-screen">{children}</main>
          <Footer />
        </body>
      </html>
    </ClerkProvider>
  );
}
