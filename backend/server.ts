/**
 * Voice Keyboard Backend Server
 * Receives audio from React Native app and transcribes using Wispr Flow SDK
 */

import { WisprClient } from 'wispr-flow-sdk-unofficial';

const PORT = process.env.PORT || 3002;

// Credentials from environment
const config = {
  email: process.env.WISPR_EMAIL,
  password: process.env.WISPR_PASSWORD,
  supabaseUrl: process.env.SUPABASE_URL,
  supabaseAnonKey: process.env.SUPABASE_ANON_KEY,
  basetenUrl: process.env.BASETEN_URL,
  basetenApiKey: process.env.BASETEN_API_KEY,
};

// Validate required config
const missingKeys = Object.entries(config)
  .filter(([_, value]) => !value)
  .map(([key]) => key);

if (missingKeys.length > 0) {
  console.error(`Missing required environment variables: ${missingKeys.join(', ')}`);
  process.exit(1);
}

// Global client instance (reused across requests)
let wisprClient: WisprClient | null = null;

async function getClient(): Promise<WisprClient> {
  if (wisprClient) {
    return wisprClient;
  }

  console.log('Creating Wispr client with unified config...');

  wisprClient = await WisprClient.create({
    email: config.email as string,
    password: config.password as string,
    supabaseUrl: config.supabaseUrl as string,
    supabaseAnonKey: config.supabaseAnonKey as string,
    basetenUrl: config.basetenUrl as string,
    basetenApiKey: config.basetenApiKey as string,
  });

  const clientConfig = wisprClient.getConfig();
  console.log(`Authenticated as user ${clientConfig.userUuid}`);

  // Warmup the service to reduce latency
  console.log('Warming up transcription service...');
  await wisprClient.warmup();
  console.log('Service ready!');

  return wisprClient;
}

// Initialize client on startup
getClient().catch((err) => {
  console.error('Failed to initialize Wispr client:', err);
});

const server = Bun.serve({
  port: PORT,

  async fetch(req) {
    const url = new URL(req.url);

    // CORS headers
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    };

    // Handle preflight
    if (req.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // Health check
    if (url.pathname === '/health') {
      return Response.json(
        { status: 'ok', authenticated: !!wisprClient },
        { headers: corsHeaders }
      );
    }

    // Transcribe endpoint
    if (url.pathname === '/transcribe' && req.method === 'POST') {
      try {
        const rawBody = (await req.json()) as { audio?: string };
        const audioBase64 = rawBody.audio;

        if (!audioBase64) {
          return Response.json(
            { error: "Missing 'audio' field in request body" },
            { status: 400, headers: corsHeaders }
          );
        }

        console.log(`Received audio: ${Math.round(audioBase64.length / 1024)}KB base64`);

        // Get client and transcribe
        const client = await getClient();
        const result = await client.transcribe({
          audioData: audioBase64,
          languages: ['en'],
        });

        const text = result.pipeline_text || result.asr_text || '';

        console.log(`Transcribed: "${text}"`);
        console.log(
          `  Status: ${result.status}, ASR time: ${result.asr_time}ms, Total: ${result.total_time}ms`
        );

        return Response.json(
          {
            status: 'success',
            text: text,
            raw: {
              asr_text: result.asr_text,
              llm_text: result.llm_text,
              pipeline_text: result.pipeline_text,
              status: result.status,
              total_time: result.total_time,
            },
          },
          { headers: corsHeaders }
        );
      } catch (error) {
        console.error('Transcription error:', error);
        return Response.json(
          {
            status: 'error',
            error: error instanceof Error ? error.message : 'Unknown error',
          },
          { status: 500, headers: corsHeaders }
        );
      }
    }

    // 404 for everything else
    return Response.json({ error: 'Not found' }, { status: 404, headers: corsHeaders });
  },
});

console.log(`Voice Keyboard Backend running on http://localhost:${PORT}`);
console.log('Endpoints:');
console.log('  GET  /health     - Health check');
console.log('  POST /transcribe - Transcribe audio (body: { audio: base64 })');
