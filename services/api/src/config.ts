import dotenv from 'dotenv';
import { z } from 'zod';

dotenv.config();

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  API_PORT: z.coerce.number().default(4000),
  API_HOST: z.string().default('0.0.0.0'),
  SUPABASE_JWT_SECRET: z.string().min(16).default('development-supabase-jwt-secret-at-least-32-chars!'),
  SUPABASE_URL: z.string().url().default('https://placeholder-project.supabase.co'),
  SUPABASE_SERVICE_ROLE_KEY: z.string().default('placeholder-service-role-key'),
  FIREBASE_PROJECT_ID: z.string().default('connectx-dev'),
  CLOUDINARY_CLOUD_NAME: z.string().default('connectx-cloud'),
  CLOUDINARY_API_KEY: z.string().default('123456789012345'),
  CLOUDINARY_API_SECRET: z.string().default('placeholder-cloudinary-secret'),
  TURN_SECRET: z.string().default('development-turn-secret'),
  TURN_SERVERS: z.string().default('turn:turn.connectx.app:3478'),
  TURN_USERNAME: z.string().optional(),
  TURN_CREDENTIAL: z.string().optional(),
});

const parsed = envSchema.safeParse(process.env);

if (!parsed.success) {
  console.error('Invalid environment variables:', parsed.error.format());
  throw new Error('Environment configuration validation failed');
}

export const config = parsed.data;
