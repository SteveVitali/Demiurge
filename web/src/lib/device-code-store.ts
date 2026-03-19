/**
 * In-memory device code store for the device authorization flow.
 * For MVP, codes are stored in-process memory and expire after 10 minutes.
 * In production, replace with Vercel KV or Redis.
 */

import { randomBytes } from 'crypto';

export interface DeviceCodeEntry {
  userCode: string;
  createdAt: number;
  expiresAt: number;
  clerkUserId: string | null;
  userEmail: string | null;
  licenseKey: string | null;
  planTier: string | null;
}

/** device_code → DeviceCodeEntry */
const store = new Map<string, DeviceCodeEntry>();

const DEVICE_CODE_TTL_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Generate a random user code formatted as XXXX-XXXX.
 */
function generateUserCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no O/0/I/1 to avoid confusion
  let code = '';
  const bytes = randomBytes(8);
  for (let i = 0; i < 8; i++) {
    code += chars[bytes[i] % chars.length];
  }
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

/**
 * Generate a random device code (32-byte hex string).
 */
function generateDeviceCode(): string {
  return `dc_${randomBytes(32).toString('hex')}`;
}

/**
 * Periodically clean up expired entries.
 */
function cleanupExpired(): void {
  const now = Date.now();
  for (const [key, entry] of store.entries()) {
    if (entry.expiresAt < now) {
      store.delete(key);
    }
  }
}

/**
 * Create a new device code entry. Returns the device_code and user_code.
 */
export function createDeviceCode(): {
  deviceCode: string;
  userCode: string;
  expiresIn: number;
  pollInterval: number;
} {
  cleanupExpired();

  const deviceCode = generateDeviceCode();
  const userCode = generateUserCode();
  const now = Date.now();

  store.set(deviceCode, {
    userCode,
    createdAt: now,
    expiresAt: now + DEVICE_CODE_TTL_MS,
    clerkUserId: null,
    userEmail: null,
    licenseKey: null,
    planTier: null,
  });

  return {
    deviceCode,
    userCode,
    expiresIn: 600, // 10 minutes in seconds
    pollInterval: 5,
  };
}

/**
 * Look up a device code entry.
 */
export function getDeviceCodeEntry(
  deviceCode: string,
): DeviceCodeEntry | null {
  cleanupExpired();
  return store.get(deviceCode) ?? null;
}

/**
 * Authorize a device code — called when the user completes sign-in and enters the code.
 * Returns true if the code was found and authorized.
 */
export function authorizeDeviceCode(
  userCode: string,
  params: {
    clerkUserId: string;
    userEmail: string;
    licenseKey: string;
    planTier: string;
  },
): boolean {
  cleanupExpired();

  for (const [, entry] of store.entries()) {
    if (entry.userCode === userCode && entry.clerkUserId === null) {
      entry.clerkUserId = params.clerkUserId;
      entry.userEmail = params.userEmail;
      entry.licenseKey = params.licenseKey;
      entry.planTier = params.planTier;
      return true;
    }
  }
  return false;
}

/**
 * Check the status of a device code. Returns:
 * - 'pending' if the code exists but hasn't been authorized
 * - 'authorized' with user data if the code has been authorized
 * - 'expired' if the code doesn't exist or has expired
 */
export function pollDeviceCode(deviceCode: string):
  | { status: 'pending' }
  | { status: 'authorized'; licenseKey: string; planTier: string; userEmail: string }
  | { status: 'expired' } {
  cleanupExpired();

  const entry = store.get(deviceCode);
  if (!entry) {
    return { status: 'expired' };
  }

  if (entry.clerkUserId) {
    // Clean up after successful authorization
    store.delete(deviceCode);
    return {
      status: 'authorized',
      licenseKey: entry.licenseKey!,
      planTier: entry.planTier!,
      userEmail: entry.userEmail!,
    };
  }

  return { status: 'pending' };
}
