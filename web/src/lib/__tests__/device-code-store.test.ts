/**
 * Tests for the device code store (in-memory MVP).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  createDeviceCode,
  getDeviceCodeEntry,
  authorizeDeviceCode,
  pollDeviceCode,
} from '../device-code-store';

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('createDeviceCode', () => {
  it('returns a device_code, user_code, expires_in, and poll_interval', () => {
    const result = createDeviceCode();

    expect(result.deviceCode).toMatch(/^dc_[a-f0-9]{64}$/);
    expect(result.userCode).toMatch(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/);
    expect(result.expiresIn).toBe(600);
    expect(result.pollInterval).toBe(5);
  });

  it('generates unique codes each time', () => {
    const a = createDeviceCode();
    const b = createDeviceCode();

    expect(a.deviceCode).not.toBe(b.deviceCode);
    expect(a.userCode).not.toBe(b.userCode);
  });
});

describe('getDeviceCodeEntry', () => {
  it('returns the entry for a valid device code', () => {
    const { deviceCode, userCode } = createDeviceCode();
    const entry = getDeviceCodeEntry(deviceCode);

    expect(entry).not.toBeNull();
    expect(entry!.userCode).toBe(userCode);
    expect(entry!.clerkUserId).toBeNull();
  });

  it('returns null for an unknown device code', () => {
    const entry = getDeviceCodeEntry('dc_nonexistent');
    expect(entry).toBeNull();
  });
});

describe('authorizeDeviceCode', () => {
  it('authorizes a pending device code by user_code', () => {
    const { userCode } = createDeviceCode();

    const authorized = authorizeDeviceCode(userCode, {
      clerkUserId: 'user-123',
      userEmail: 'test@example.com',
      licenseKey: 'DEMI-TEST-KEY',
      planTier: 'starter',
    });

    expect(authorized).toBe(true);
  });

  it('returns false for an unknown user_code', () => {
    const authorized = authorizeDeviceCode('ZZZZ-ZZZZ', {
      clerkUserId: 'user-123',
      userEmail: 'test@example.com',
      licenseKey: 'DEMI-TEST-KEY',
      planTier: 'starter',
    });

    expect(authorized).toBe(false);
  });

  it('returns false if the code was already authorized', () => {
    const { userCode } = createDeviceCode();

    authorizeDeviceCode(userCode, {
      clerkUserId: 'user-123',
      userEmail: 'test@example.com',
      licenseKey: 'DEMI-TEST-KEY',
      planTier: 'starter',
    });

    // Second attempt with same code should fail
    const secondAttempt = authorizeDeviceCode(userCode, {
      clerkUserId: 'user-456',
      userEmail: 'other@example.com',
      licenseKey: 'DEMI-OTHER-KEY',
      planTier: 'pro',
    });

    expect(secondAttempt).toBe(false);
  });
});

describe('pollDeviceCode', () => {
  it('returns pending for a new device code', () => {
    const { deviceCode } = createDeviceCode();
    const result = pollDeviceCode(deviceCode);

    expect(result.status).toBe('pending');
  });

  it('returns authorized with user data after authorization', () => {
    const { deviceCode, userCode } = createDeviceCode();

    authorizeDeviceCode(userCode, {
      clerkUserId: 'user-123',
      userEmail: 'test@example.com',
      licenseKey: 'DEMI-ABCD-EFGH',
      planTier: 'pro',
    });

    const result = pollDeviceCode(deviceCode);

    expect(result.status).toBe('authorized');
    if (result.status === 'authorized') {
      expect(result.licenseKey).toBe('DEMI-ABCD-EFGH');
      expect(result.planTier).toBe('pro');
      expect(result.userEmail).toBe('test@example.com');
    }
  });

  it('returns expired for an unknown device code', () => {
    const result = pollDeviceCode('dc_nonexistent');
    expect(result.status).toBe('expired');
  });

  it('consumes the device code after successful poll', () => {
    const { deviceCode, userCode } = createDeviceCode();

    authorizeDeviceCode(userCode, {
      clerkUserId: 'user-123',
      userEmail: 'test@example.com',
      licenseKey: 'DEMI-KEY',
      planTier: 'starter',
    });

    // First poll — authorized
    const first = pollDeviceCode(deviceCode);
    expect(first.status).toBe('authorized');

    // Second poll — expired (entry was deleted)
    const second = pollDeviceCode(deviceCode);
    expect(second.status).toBe('expired');
  });
});
