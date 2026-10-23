export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface LogEntry {
  level: LogLevel;
  tag: string;
  message: string;
  metadata?: Record<string, unknown>;
  timestamp: string;
}

const REDACTED_KEYS = new Set([
  'token',
  'accesstoken',
  'refreshtoken',
  'idtoken',
  'password',
  'secret',
  'authorization',
  'cookie',
  'sdp',
  'credential',
  'apikey',
]);

export function redactSensitiveData(obj: unknown): unknown {
  if (obj === null || obj === undefined) return obj;
  if (typeof obj === 'string') {
    // Redact JWT pattern
    if (/^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+$/.test(obj)) {
      return '[REDACTED_JWT]';
    }
    // Redact SDP blobs
    if (obj.includes('v=0\r\no=') || obj.includes('a=fingerprint:')) {
      return '[REDACTED_SDP_PAYLOAD]';
    }
    return obj;
  }
  if (Array.isArray(obj)) {
    return obj.map(redactSensitiveData);
  }
  if (typeof obj === 'object') {
    const sanitized: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      const lowerKey = key.toLowerCase();
      if (REDACTED_KEYS.has(lowerKey)) {
        sanitized[key] = '[REDACTED]';
      } else {
        sanitized[key] = redactSensitiveData(value);
      }
    }
    return sanitized;
  }
  return obj;
}

export class StructuredLogger {
  private logs: LogEntry[] = [];
  private maxLogs = 500;

  public log(level: LogLevel, tag: string, message: string, metadata?: Record<string, unknown>): LogEntry {
    const sanitizedMetadata = metadata ? (redactSensitiveData(metadata) as Record<string, unknown>) : undefined;
    const entry: LogEntry = {
      level,
      tag,
      message,
      metadata: sanitizedMetadata,
      timestamp: new Date().toISOString(),
    };

    this.logs.push(entry);
    if (this.logs.length > this.maxLogs) {
      this.logs.shift();
    }

    if (process.env.NODE_ENV !== 'test') {
      const formatted = `[${entry.timestamp}] [${level.toUpperCase()}] [${tag}]: ${message}`;
      if (level === 'error') {
        console.error(formatted, sanitizedMetadata || '');
      } else if (level === 'warn') {
        console.warn(formatted, sanitizedMetadata || '');
      } else {
        console.log(formatted, sanitizedMetadata || '');
      }
    }

    return entry;
  }

  public debug(tag: string, message: string, metadata?: Record<string, unknown>): LogEntry {
    return this.log('debug', tag, message, metadata);
  }

  public info(tag: string, message: string, metadata?: Record<string, unknown>): LogEntry {
    return this.log('info', tag, message, metadata);
  }

  public warn(tag: string, message: string, metadata?: Record<string, unknown>): LogEntry {
    return this.log('warn', tag, message, metadata);
  }

  public error(tag: string, message: string, metadata?: Record<string, unknown>): LogEntry {
    return this.log('error', tag, message, metadata);
  }

  public getRecentLogs(): LogEntry[] {
    return [...this.logs];
  }

  public clear(): void {
    this.logs = [];
  }
}

export const logger = new StructuredLogger();
