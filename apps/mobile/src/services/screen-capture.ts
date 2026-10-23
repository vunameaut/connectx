import type {
  ScreenCaptureState,
  ScreenCaptureConfig,
  ScreenCaptureError,
  NativeScreenCaptureModuleSpec,
} from '@connectx/shared-types';

export type ScreenCaptureEventListener = (event: {
  state: ScreenCaptureState;
  error?: ScreenCaptureError;
}) => void;

export class ScreenCaptureService implements NativeScreenCaptureModuleSpec {
  private currentState: ScreenCaptureState = 'idle';
  private listeners: Set<ScreenCaptureEventListener> = new Set();
  private mockFailWith: ScreenCaptureError['code'] | null = null;

  public getState(): ScreenCaptureState {
    return this.currentState;
  }

  public subscribe(listener: ScreenCaptureEventListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify(state: ScreenCaptureState, error?: ScreenCaptureError): void {
    this.currentState = state;
    this.listeners.forEach((fn) => fn({ state, error }));
  }

  /**
   * For testing: configure simulated native failures.
   */
  public setMockFailure(code: ScreenCaptureError['code'] | null): void {
    this.mockFailWith = code;
  }

  /**
   * Initiates OS Screen Sharing.
   * Prompts user with native consent dialog (Android MediaProjection / iOS ReplayKit).
   */
  public async requestScreenCapture(config?: Partial<ScreenCaptureConfig>): Promise<boolean> {
    if (this.currentState === 'capturing') {
      return true;
    }

    this.notify('requesting_permission');

    // Simulate native platform execution
    if (this.mockFailWith) {
      const errorCode = this.mockFailWith;
      this.mockFailWith = null;
      const error: ScreenCaptureError = {
        code: errorCode,
        message:
          errorCode === 'USER_CANCELLED'
            ? 'User dismissed system screen capture prompt'
            : errorCode === 'SECURITY_EXCEPTION'
            ? 'Foreground service mediaProjection requirement failed'
            : 'Screen capture failed',
      };
      this.notify(errorCode === 'USER_CANCELLED' ? 'permission_denied' : 'error', error);
      return false;
    }

    // Success flow
    this.notify('starting');
    this.notify('capturing');
    return true;
  }

  /**
   * Stops screen capture.
   * Can be triggered from in-app button, OS notification, or call hangup.
   */
  public async stopScreenCapture(): Promise<boolean> {
    if (this.currentState === 'idle') {
      return true;
    }

    this.notify('stopping');
    this.notify('idle');
    return true;
  }

  public async isScreenCaptureActive(): Promise<boolean> {
    return this.currentState === 'capturing';
  }

  /**
   * Callback invoked when Android MediaProjection.Callback.onStop() or iOS broadcastFinished occurs.
   */
  public handleOsRevocation(): void {
    this.notify('idle', {
      code: 'OS_REVOKED',
      message: 'Operating system or user stopped screen sharing via system controls',
    });
  }
}

export const screenCaptureService = new ScreenCaptureService();
