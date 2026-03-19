/**
 * Transactional email via Resend — Spec Plan §6 / Phase 3.
 *
 * Sends:
 * - Welcome email on sign-up
 * - Trial expiring warning (3 days before)
 * - Subscription receipt / renewal confirmation
 *
 * Resend is free up to 3k emails/month.
 * The RESEND_API_KEY env var must be set for email delivery to be active.
 * If not set, emails are logged to console (development mode).
 */

import { Resend } from 'resend';

const FROM_ADDRESS = 'Demiurge <noreply@demiurge.dev>';

function getResend(): Resend | null {
  const apiKey = process.env.RESEND_API_KEY;
  if (!apiKey) return null;
  return new Resend(apiKey);
}

export interface SendEmailParams {
  to: string;
  subject: string;
  html: string;
  text?: string;
}

async function sendEmail(params: SendEmailParams): Promise<boolean> {
  const resend = getResend();
  if (!resend) {
    console.log(`[email] (dev mode) To: ${params.to} | Subject: ${params.subject}`);
    return true;
  }

  try {
    const { error } = await resend.emails.send({
      from: FROM_ADDRESS,
      to: params.to,
      subject: params.subject,
      html: params.html,
      text: params.text,
    });
    if (error) {
      console.error(`[email] Failed to send to ${params.to}:`, error);
      return false;
    }
    return true;
  } catch (err) {
    console.error(`[email] Error sending to ${params.to}:`, err);
    return false;
  }
}

export async function sendWelcomeEmail(email: string, licenseKey: string): Promise<boolean> {
  return sendEmail({
    to: email,
    subject: 'Welcome to Demiurge — Your Trial License Key',
    html: `
      <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 560px; margin: 0 auto; padding: 40px 20px;">
        <h1 style="font-size: 24px; font-weight: 700; color: #fafafa; margin-bottom: 16px;">Welcome to Demiurge</h1>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          Your 14-day free trial is active. Here's your license key:
        </p>
        <div style="background: #171717; border: 1px solid #262626; border-radius: 8px; padding: 16px; margin: 24px 0;">
          <code style="font-family: 'SF Mono', Monaco, Consolas, monospace; font-size: 16px; color: #6366f1; letter-spacing: 1px;">
            ${licenseKey}
          </code>
        </div>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          Get started in your terminal:
        </p>
        <pre style="background: #171717; border: 1px solid #262626; border-radius: 8px; padding: 16px; margin: 16px 0; color: #fafafa; font-size: 13px;">demiurge login --license-key ${licenseKey}
demiurge init --smart
demiurge run</pre>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          Your trial includes 5 runs and 50K agent tokens.
          <a href="https://demiurge.dev/pricing" style="color: #6366f1;">Upgrade anytime</a> for more.
        </p>
        <hr style="border: none; border-top: 1px solid #262626; margin: 32px 0;" />
        <p style="color: #737373; font-size: 12px;">
          Demiurge — AI-powered web development automation.
          <a href="https://demiurge.dev" style="color: #6366f1;">demiurge.dev</a>
        </p>
      </div>
    `,
    text: `Welcome to Demiurge!\n\nYour license key: ${licenseKey}\n\nGet started:\n  demiurge login --license-key ${licenseKey}\n  demiurge init --smart\n  demiurge run\n\nYour trial includes 5 runs and 50K agent tokens. Upgrade at https://demiurge.dev/pricing`,
  });
}

export async function sendTrialExpiringEmail(email: string, daysRemaining: number): Promise<boolean> {
  return sendEmail({
    to: email,
    subject: `Your Demiurge trial expires in ${daysRemaining} day${daysRemaining === 1 ? '' : 's'}`,
    html: `
      <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 560px; margin: 0 auto; padding: 40px 20px;">
        <h1 style="font-size: 24px; font-weight: 700; color: #fafafa; margin-bottom: 16px;">Your trial is ending soon</h1>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          Your Demiurge trial expires in <strong style="color: #eab308;">${daysRemaining} day${daysRemaining === 1 ? '' : 's'}</strong>.
          Upgrade to keep using agentic verification and repair.
        </p>
        <a href="https://demiurge.dev/pricing"
           style="display: inline-block; background: #6366f1; color: white; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-weight: 600; font-size: 14px; margin: 24px 0;">
          Upgrade Now
        </a>
        <p style="color: #737373; font-size: 12px; margin-top: 32px;">
          Demiurge — <a href="https://demiurge.dev" style="color: #6366f1;">demiurge.dev</a>
        </p>
      </div>
    `,
    text: `Your Demiurge trial expires in ${daysRemaining} day(s). Upgrade at https://demiurge.dev/pricing`,
  });
}

export async function sendSubscriptionReceiptEmail(
  email: string,
  planTier: string,
  amount: string,
): Promise<boolean> {
  return sendEmail({
    to: email,
    subject: `Demiurge ${planTier} subscription confirmed`,
    html: `
      <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 560px; margin: 0 auto; padding: 40px 20px;">
        <h1 style="font-size: 24px; font-weight: 700; color: #fafafa; margin-bottom: 16px;">Subscription confirmed</h1>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          You're now on the <strong style="color: #22c55e;">${planTier}</strong> plan (${amount}).
        </p>
        <p style="color: #a3a3a3; font-size: 14px; line-height: 1.6;">
          Manage your subscription at any time from your
          <a href="https://demiurge.dev/account" style="color: #6366f1;">account page</a>.
        </p>
        <hr style="border: none; border-top: 1px solid #262626; margin: 32px 0;" />
        <p style="color: #737373; font-size: 12px;">
          Demiurge — <a href="https://demiurge.dev" style="color: #6366f1;">demiurge.dev</a>
        </p>
      </div>
    `,
    text: `Your Demiurge ${planTier} subscription is confirmed (${amount}). Manage at https://demiurge.dev/account`,
  });
}
