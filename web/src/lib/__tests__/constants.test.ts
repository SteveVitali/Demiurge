/**
 * Tests for plan constants and mapping functions.
 */

import { describe, it, expect, vi } from 'vitest';

vi.mock('@/lib/env', () => ({
  env: {
    KEYGEN_TRIAL_POLICY_ID: 'policy-trial',
    KEYGEN_STARTER_POLICY_ID: 'policy-starter',
    KEYGEN_PRO_POLICY_ID: 'policy-pro',
    KEYGEN_TEAM_POLICY_ID: 'policy-team',
    STRIPE_STARTER_PRICE_ID: 'price_starter_m',
    STRIPE_STARTER_ANNUAL_PRICE_ID: 'price_starter_a',
    STRIPE_PRO_PRICE_ID: 'price_pro_m',
    STRIPE_PRO_ANNUAL_PRICE_ID: 'price_pro_a',
    STRIPE_TEAM_PRICE_ID: 'price_team_m',
    STRIPE_TEAM_ANNUAL_PRICE_ID: 'price_team_a',
  },
}));

import {
  PLAN_TIERS,
  PLAN_CONFIG,
  getPlanConfig,
  getPlanConfigMap,
  getPriceToPolicyMap,
  getPriceToTierMap,
  getPlanMaxUses,
} from '../constants';

describe('PLAN_TIERS', () => {
  it('contains all expected tiers', () => {
    expect(PLAN_TIERS).toEqual([
      'trial',
      'starter',
      'pro',
      'team',
      'enterprise',
    ]);
  });
});

describe('PLAN_CONFIG', () => {
  it('maps trial to correct limits', () => {
    expect(PLAN_CONFIG.trial.maxRuns).toBe(5);
    expect(PLAN_CONFIG.trial.maxMachines).toBe(1);
    expect(PLAN_CONFIG.trial.maxTokens).toBe(50_000);
    expect(PLAN_CONFIG.trial.keygenPolicyId).toBe('policy-trial');
  });

  it('maps starter to correct limits', () => {
    expect(PLAN_CONFIG.starter.maxRuns).toBe(50);
    expect(PLAN_CONFIG.starter.maxMachines).toBe(2);
    expect(PLAN_CONFIG.starter.maxTokens).toBe(500_000);
  });

  it('maps pro to correct limits', () => {
    expect(PLAN_CONFIG.pro.maxRuns).toBe(200);
    expect(PLAN_CONFIG.pro.maxMachines).toBe(3);
    expect(PLAN_CONFIG.pro.maxTokens).toBe(2_000_000);
  });

  it('maps team to correct limits', () => {
    expect(PLAN_CONFIG.team.maxRuns).toBe(150);
    expect(PLAN_CONFIG.team.maxMachines).toBe(5);
    expect(PLAN_CONFIG.team.maxTokens).toBe(1_500_000);
  });

  it('maps enterprise to unlimited (-1)', () => {
    expect(PLAN_CONFIG.enterprise.maxRuns).toBe(-1);
    expect(PLAN_CONFIG.enterprise.maxMachines).toBe(-1);
    expect(PLAN_CONFIG.enterprise.maxTokens).toBe(-1);
  });
});

describe('getPlanConfig', () => {
  it('returns full config for a tier', () => {
    const config = getPlanConfig('starter');
    expect(config.keygenPolicyId).toBe('policy-starter');
    expect(config.maxRuns).toBe(50);
    expect(config.maxMachines).toBe(2);
    expect(config.maxTokens).toBe(500_000);
  });
});

describe('getPlanConfigMap', () => {
  it('returns all 5 tiers', () => {
    const map = getPlanConfigMap();
    expect(Object.keys(map)).toHaveLength(5);
    expect(map.trial.maxRuns).toBe(5);
    expect(map.enterprise.maxRuns).toBe(-1);
  });
});

describe('getPriceToPolicyMap', () => {
  it('maps all 6 Stripe price IDs to correct Keygen policies', () => {
    const map = getPriceToPolicyMap();
    expect(Object.keys(map)).toHaveLength(6);
    expect(map['price_starter_m']).toBe('policy-starter');
    expect(map['price_starter_a']).toBe('policy-starter');
    expect(map['price_pro_m']).toBe('policy-pro');
    expect(map['price_pro_a']).toBe('policy-pro');
    expect(map['price_team_m']).toBe('policy-team');
    expect(map['price_team_a']).toBe('policy-team');
  });
});

describe('getPriceToTierMap', () => {
  it('maps Stripe price IDs to plan tiers', () => {
    const map = getPriceToTierMap();
    expect(map['price_starter_m']).toBe('starter');
    expect(map['price_starter_a']).toBe('starter');
    expect(map['price_pro_m']).toBe('pro');
    expect(map['price_pro_a']).toBe('pro');
    expect(map['price_team_m']).toBe('team');
    expect(map['price_team_a']).toBe('team');
  });
});

describe('getPlanMaxUses', () => {
  it('returns correct maxUses for each tier', () => {
    expect(getPlanMaxUses('trial')).toBe(5);
    expect(getPlanMaxUses('starter')).toBe(50);
    expect(getPlanMaxUses('pro')).toBe(200);
    expect(getPlanMaxUses('team')).toBe(150);
    expect(getPlanMaxUses('enterprise')).toBe(-1);
  });
});
