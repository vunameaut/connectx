import crypto from 'node:crypto';
import { config } from '../config.js';

export interface SignUploadParams {
  conversationId: string;
  resourceType: 'image' | 'video' | 'raw';
  mimeType: string;
  sizeBytes: number;
}

export interface SignedUploadTicket {
  cloudName: string;
  apiKey: string;
  timestamp: number;
  folder: string;
  signature: string;
  uploadUrl: string;
  maxSizeBytes: number;
}

// Security limits on upload size
const MAX_SIZES_BYTES: Record<string, number> = {
  image: 10 * 1024 * 1024, // 10 MB
  video: 50 * 1024 * 1024, // 50 MB
  raw: 25 * 1024 * 1024,   // 25 MB
};

const ALLOWED_MIME_PREFIXES: Record<string, string[]> = {
  image: ['image/jpeg', 'image/png', 'image/webp', 'image/gif'],
  video: ['video/mp4', 'video/webm', 'video/quicktime'],
  raw: ['application/pdf', 'application/zip', 'text/plain'],
};

export class CloudinarySigner {
  public signUpload(params: SignUploadParams): SignedUploadTicket {
    const maxAllowed = MAX_SIZES_BYTES[params.resourceType] || MAX_SIZES_BYTES.raw;
    if (params.sizeBytes > maxAllowed) {
      throw new Error(`FILE_TOO_LARGE: Maximum allowed size for ${params.resourceType} is ${maxAllowed / 1024 / 1024}MB`);
    }

    const allowedMimes = ALLOWED_MIME_PREFIXES[params.resourceType] || [];
    if (!allowedMimes.includes(params.mimeType)) {
      throw new Error(`DISALLOWED_MIME_TYPE: ${params.mimeType} is not permitted for ${params.resourceType}`);
    }

    const timestamp = Math.floor(Date.now() / 1000);
    const folder = `connectx/conversations/${params.conversationId}`;

    // Cloudinary signature requires sorting parameters alphabetically and hashing with API Secret:
    // "folder=<folder>&timestamp=<timestamp><api_secret>"
    const toSign = `folder=${folder}&timestamp=${timestamp}${config.CLOUDINARY_API_SECRET}`;
    const signature = crypto.createHash('sha1').update(toSign).digest('hex');

    const uploadUrl = `https://api.cloudinary.com/v1_1/${config.CLOUDINARY_CLOUD_NAME}/${params.resourceType}/upload`;

    return {
      cloudName: config.CLOUDINARY_CLOUD_NAME,
      apiKey: config.CLOUDINARY_API_KEY,
      timestamp,
      folder,
      signature,
      uploadUrl,
      maxSizeBytes: maxAllowed,
    };
  }
}

export const cloudinarySigner = new CloudinarySigner();
