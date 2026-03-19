/**
 * POST /api/license/activate
 *
 * Activate a machine for a license. Creates a machine entry in Keygen
 * associated with the license.
 *
 * Authentication: License key in X-License-Key header.
 */

import { NextRequest, NextResponse } from 'next/server';
import { keygen, KeygenApiError } from '@/lib/keygen';

interface ActivateRequestBody {
  fingerprint: string;
  name: string;
  platform: string;
  hostname?: string;
}

export async function POST(req: NextRequest) {
  const licenseKey = req.headers.get('x-license-key');
  if (!licenseKey) {
    return NextResponse.json(
      { error: 'Missing X-License-Key header' },
      { status: 401 },
    );
  }

  let body: ActivateRequestBody;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json(
      { error: 'Invalid JSON body' },
      { status: 400 },
    );
  }

  if (!body.fingerprint || !body.name || !body.platform) {
    return NextResponse.json(
      { error: 'Missing required fields: fingerprint, name, platform' },
      { status: 400 },
    );
  }

  try {
    // First, validate the license key to get the license ID
    const validation = await keygen.validateLicenseKey(licenseKey);
    const licenseId = validation.metadata.id;

    if (!licenseId) {
      return NextResponse.json(
        { activated: false, code: 'NOT_FOUND', message: 'Invalid license key' },
        { status: 404 },
      );
    }

    // Activate the machine
    const machine = await keygen.activateMachine(licenseId, {
      fingerprint: body.fingerprint,
      name: body.name,
      platform: body.platform,
      hostname: body.hostname,
    });

    return NextResponse.json({
      activated: true,
      machine_id: machine.id,
    });
  } catch (err) {
    if (err instanceof KeygenApiError) {
      // Machine limit exceeded
      if (err.code === 'MACHINE_LIMIT_EXCEEDED') {
        return NextResponse.json(
          {
            activated: false,
            code: 'TOO_MANY_MACHINES',
            message:
              'Machine limit reached for your plan. Deactivate an existing machine or upgrade.',
          },
          { status: 422 },
        );
      }

      // 409 — machine already exists (fingerprint conflict)
      if (err.status === 409) {
        return NextResponse.json(
          {
            activated: true,
            message: 'Machine already activated',
          },
          { status: 200 },
        );
      }

      console.error(`Keygen activation error: ${err.message}`);
      return NextResponse.json(
        { activated: false, code: err.code, message: err.detail },
        { status: err.status >= 500 ? 502 : err.status },
      );
    }

    console.error('Machine activation error:', err);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 },
    );
  }
}
