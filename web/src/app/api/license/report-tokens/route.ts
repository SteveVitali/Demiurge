/**
 * POST /api/license/report-tokens
 *
 * Spec 05 §4.2: Accept token usage reports from the CLI/desktop app.
 * Stored for analytics and future billing. Fire-and-forget from the client side.
 *
 * Authentication: License key in X-License-Key header.
 */

import { NextRequest, NextResponse } from 'next/server';
import { keygen } from '@/lib/keygen';

export async function POST(req: NextRequest) {
  const licenseKey = req.headers.get('x-license-key');
  if (!licenseKey) {
    return NextResponse.json(
      { error: 'Missing X-License-Key header' },
      { status: 401 },
    );
  }

  let body: { run_id?: string; input_tokens?: number; output_tokens?: number };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json(
      { error: 'Invalid JSON body' },
      { status: 400 },
    );
  }

  // Lightweight validation: just check the license key is real
  try {
    const validation = await keygen.validateLicenseKey(licenseKey);
    if (!validation.valid) {
      return NextResponse.json(
        { error: 'Invalid license key' },
        { status: 403 },
      );
    }
  } catch {
    // If validation fails due to network error, still accept the report
    // (best-effort — don't block client)
  }

  // Log for analytics (future: insert into Supabase)
  console.log(
    `[token-report] license=${licenseKey.substring(0, 8)}... run=${body.run_id ?? 'unknown'} in=${body.input_tokens ?? 0} out=${body.output_tokens ?? 0}`,
  );

  // Future: Insert into Supabase
  // await supabase.from('token_usage').insert({
  //   license_key_prefix: licenseKey.substring(0, 8),
  //   run_id: body.run_id,
  //   input_tokens: body.input_tokens,
  //   output_tokens: body.output_tokens,
  //   reported_at: new Date().toISOString(),
  // });

  return NextResponse.json({ ok: true });
}
