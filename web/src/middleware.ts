import { clerkMiddleware, createRouteMatcher } from '@clerk/nextjs/server';

/**
 * Clerk middleware configuration.
 *
 * Public routes (no auth required):
 * - Webhook endpoints (verified by their own signatures)
 * - Device code endpoints (used by unauthenticated CLI)
 * - License validation/activation (uses license key auth, not Clerk JWT)
 * - Sign-in/sign-up pages
 * - Root page
 *
 * Protected routes (Clerk JWT required):
 * - /api/user/* (subscription, portal)
 * - /api/auth/device-authorize (user must be signed in to authorize a device)
 * - /activate (device code activation page — Clerk handles auth in-page)
 */

const isPublicRoute = createRouteMatcher([
  '/',
  '/sign-in(.*)',
  '/sign-up(.*)',
  '/auth-callback',
  '/activate',
  '/api/webhooks/(.*)',
  '/api/auth/device-code',
  '/api/auth/device-poll',
  '/api/license/(.*)',
]);

export default clerkMiddleware(async (auth, req) => {
  if (!isPublicRoute(req)) {
    await auth.protect();
  }
});

export const config = {
  matcher: [
    // Skip Next.js internals and all static files
    '/((?!_next|[^?]*\\.(?:html?|css|js(?!on)|jpe?g|webp|png|gif|svg|ttf|woff2?|ico|csv|docx?|xlsx?|zip|webmanifest)).*)',
    // Always run for API routes
    '/(api|trpc)(.*)',
  ],
};
