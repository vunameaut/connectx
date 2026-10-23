import { describe, it, expect, beforeEach } from 'vitest';
import { ScreenCaptureService } from '../src/services/screen-capture.js';
import type { ScreenCaptureState } from '@connectx/shared-types';

describe('Mobile Screen Capture Service Lifecycle Tests', () => {
  let service: ScreenCaptureService;

  beforeEach(() => {
    service = new ScreenCaptureService();
  });

  it('TC-0.5.1: Initial state is idle', async () => {
    expect(service.getState()).toBe('idle');
    expect(await service.isScreenCaptureActive()).toBe(false);
  });

  it('TC-0.5.2: Successful user consent flow transitions to capturing', async () => {
    const states: ScreenCaptureState[] = [];
    service.subscribe(({ state }) => states.push(state));

    const success = await service.requestScreenCapture({
      width: 1080,
      height: 1920,
      frameRate: 15,
      bitrateBps: 1_500_000,
    });

    expect(success).toBe(true);
    expect(await service.isScreenCaptureActive()).toBe(true);
    expect(states).toEqual(['requesting_permission', 'starting', 'capturing']);
  });

  it('TC-0.5.3: User rejection (cancel system dialog) sets state to permission_denied', async () => {
    service.setMockFailure('USER_CANCELLED');

    const states: ScreenCaptureState[] = [];
    let caughtError: unknown;
    service.subscribe(({ state, error }) => {
      states.push(state);
      if (error) caughtError = error;
    });

    const success = await service.requestScreenCapture();

    expect(success).toBe(false);
    expect(await service.isScreenCaptureActive()).toBe(false);
    expect(states).toContain('permission_denied');
    expect(caughtError).toMatchObject({
      code: 'USER_CANCELLED',
    });
  });

  it('TC-0.5.4: Stopping screen capture cleans up resources and returns to idle', async () => {
    await service.requestScreenCapture();
    expect(await service.isScreenCaptureActive()).toBe(true);

    const states: ScreenCaptureState[] = [];
    service.subscribe(({ state }) => states.push(state));

    const stopped = await service.stopScreenCapture();
    expect(stopped).toBe(true);
    expect(service.getState()).toBe('idle');
    expect(await service.isScreenCaptureActive()).toBe(false);
    expect(states).toEqual(['stopping', 'idle']);
  });

  it('TC-0.5.5: OS revocation (notification stop or other app priority) triggers teardown', async () => {
    await service.requestScreenCapture();
    expect(await service.isScreenCaptureActive()).toBe(true);

    let revocationError: unknown;
    service.subscribe(({ state, error }) => {
      if (error?.code === 'OS_REVOKED') {
        revocationError = error;
      }
    });

    service.handleOsRevocation();

    expect(service.getState()).toBe('idle');
    expect(await service.isScreenCaptureActive()).toBe(false);
    expect(revocationError).toMatchObject({
      code: 'OS_REVOKED',
    });
  });
});
