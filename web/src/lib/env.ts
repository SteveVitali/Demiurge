/**
 * Typed environment variable access. Throws at startup if required vars are missing.
 */

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function optional(name: string, defaultValue: string = ''): string {
  return process.env[name] ?? defaultValue;
}

export const env = {
  // Clerk
  NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY: optional('NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY'),
  get CLERK_SECRET_KEY() { return required('CLERK_SECRET_KEY'); },
  get CLERK_WEBHOOK_SECRET() { return required('CLERK_WEBHOOK_SECRET'); },

  // Stripe
  get STRIPE_SECRET_KEY() { return required('STRIPE_SECRET_KEY'); },
  get STRIPE_WEBHOOK_SECRET() { return required('STRIPE_WEBHOOK_SECRET'); },
  get STRIPE_STARTER_PRICE_ID() { return required('STRIPE_STARTER_PRICE_ID'); },
  get STRIPE_STARTER_ANNUAL_PRICE_ID() { return required('STRIPE_STARTER_ANNUAL_PRICE_ID'); },
  get STRIPE_PRO_PRICE_ID() { return required('STRIPE_PRO_PRICE_ID'); },
  get STRIPE_PRO_ANNUAL_PRICE_ID() { return required('STRIPE_PRO_ANNUAL_PRICE_ID'); },
  get STRIPE_TEAM_PRICE_ID() { return required('STRIPE_TEAM_PRICE_ID'); },
  get STRIPE_TEAM_ANNUAL_PRICE_ID() { return required('STRIPE_TEAM_ANNUAL_PRICE_ID'); },

  // Keygen
  get KEYGEN_ACCOUNT_ID() { return required('KEYGEN_ACCOUNT_ID'); },
  get KEYGEN_PRODUCT_TOKEN() { return required('KEYGEN_PRODUCT_TOKEN'); },
  get KEYGEN_PRODUCT_ID() { return required('KEYGEN_PRODUCT_ID'); },
  get KEYGEN_TRIAL_POLICY_ID() { return required('KEYGEN_TRIAL_POLICY_ID'); },
  get KEYGEN_STARTER_POLICY_ID() { return required('KEYGEN_STARTER_POLICY_ID'); },
  get KEYGEN_PRO_POLICY_ID() { return required('KEYGEN_PRO_POLICY_ID'); },
  get KEYGEN_TEAM_POLICY_ID() { return required('KEYGEN_TEAM_POLICY_ID'); },

  // App
  NEXT_PUBLIC_APP_URL: optional('NEXT_PUBLIC_APP_URL', 'https://demiurge.dev'),
  DESKTOP_DEEP_LINK_SCHEME: optional('DESKTOP_DEEP_LINK_SCHEME', 'demiurge'),
};
