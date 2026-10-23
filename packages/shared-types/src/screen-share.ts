export type ScreenCaptureState =
  | 'idle'
  | 'requesting_permission'
  | 'permission_denied'
  | 'starting'
  | 'capturing'
  | 'stopping'
  | 'error';

export interface ScreenCaptureConfig {
  width: number;
  height: number;
  frameRate: number;
  bitrateBps: number;
}

export interface ScreenCaptureError {
  code:
    | 'USER_CANCELLED'
    | 'SECURITY_EXCEPTION'
    | 'FOREGROUND_SERVICE_FAILED'
    | 'REPLAYKIT_FAILED'
    | 'PROTECTED_CONTENT_DETECTED'
    | 'OS_REVOKED';
  message: string;
}

export interface NativeScreenCaptureModuleSpec {
  requestScreenCapture(config?: Partial<ScreenCaptureConfig>): Promise<boolean>;
  stopScreenCapture(): Promise<boolean>;
  isScreenCaptureActive(): Promise<boolean>;
}
