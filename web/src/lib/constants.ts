import { env } from './env';

export const PLAN_TIERS = ['trial', 'starter', 'pro', 'team', 'enterprise'] as const;
export type PlanTier = (typeof PLAN_TIERS)[number];

export interface PlanConfig {
  keygenPolicyId: string;
  maxRuns: number;
  maxMachines: number;
  maxTokens: number;
}

/** Static limits per tier (no env access at module scope). */
const PLAN_LIMITS: Record<PlanTier, Omit<PlanConfig, 'keygenPolicyId'>> = {
  trial:      { maxRuns: 5,   maxMachines: 1, maxTokens: 50_000 },
  starter:    { maxRuns: 50,  maxMachines: 2, maxTokens: 500_000 },
  pro:        { maxRuns: 200, maxMachines: 3, maxTokens: 2_000_000 },
  team:       { maxRuns: 150, maxMachines: 5, maxTokens: 1_500_000 },
  enterprise: { maxRuns: -1,  maxMachines: -1, maxTokens: -1 },
};

/** Plan tier → Keygen policy + limits. Lazily resolved to avoid build-time env access. */
export function getPlanConfig(tier: PlanTier): PlanConfig {
  const limits = PLAN_LIMITS[tier];
  const policyMap: Record<string, () => string> = {
    trial:      () => env.KEYGEN_TRIAL_POLICY_ID,
    starter:    () => env.KEYGEN_STARTER_POLICY_ID,
    pro:        () => env.KEYGEN_PRO_POLICY_ID,
    team:       () => env.KEYGEN_TEAM_POLICY_ID,
    enterprise: () => '',
  };
  return { keygenPolicyId: policyMap[tier](), ...limits };
}

/** Full PLAN_CONFIG as a lazy getter for backward compatibility. */
export function getPlanConfigMap(): Record<PlanTier, PlanConfig> {
  return Object.fromEntries(
    PLAN_TIERS.map((tier) => [tier, getPlanConfig(tier)]),
  ) as Record<PlanTier, PlanConfig>;
}

/** @deprecated Use getPlanConfig() or getPlanConfigMap() instead. */
export const PLAN_CONFIG = new Proxy({} as Record<PlanTier, PlanConfig>, {
  get(_, prop: string) {
    if (PLAN_TIERS.includes(prop as PlanTier)) {
      return getPlanConfig(prop as PlanTier);
    }
    return undefined;
  },
});

/** Stripe price ID → Keygen policy ID */
export function getPriceToPolicyMap(): Record<string, string> {
  return {
    [env.STRIPE_STARTER_PRICE_ID]: env.KEYGEN_STARTER_POLICY_ID,
    [env.STRIPE_STARTER_ANNUAL_PRICE_ID]: env.KEYGEN_STARTER_POLICY_ID,
    [env.STRIPE_PRO_PRICE_ID]: env.KEYGEN_PRO_POLICY_ID,
    [env.STRIPE_PRO_ANNUAL_PRICE_ID]: env.KEYGEN_PRO_POLICY_ID,
    [env.STRIPE_TEAM_PRICE_ID]: env.KEYGEN_TEAM_POLICY_ID,
    [env.STRIPE_TEAM_ANNUAL_PRICE_ID]: env.KEYGEN_TEAM_POLICY_ID,
  };
}

/** Stripe price ID → plan tier */
export function getPriceToTierMap(): Record<string, PlanTier> {
  return {
    [env.STRIPE_STARTER_PRICE_ID]: 'starter',
    [env.STRIPE_STARTER_ANNUAL_PRICE_ID]: 'starter',
    [env.STRIPE_PRO_PRICE_ID]: 'pro',
    [env.STRIPE_PRO_ANNUAL_PRICE_ID]: 'pro',
    [env.STRIPE_TEAM_PRICE_ID]: 'team',
    [env.STRIPE_TEAM_ANNUAL_PRICE_ID]: 'team',
  };
}

/** Plan tier → max uses (runs per billing period) */
export function getPlanMaxUses(tier: PlanTier): number {
  return PLAN_LIMITS[tier]?.maxRuns ?? 0;
}
