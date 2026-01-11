# Voice Keyboard Backend

Transcription server for Voice Keyboard using [wispr-flow-sdk-unofficial](https://github.com/sahilchouksey/wispr-flow-sdk).

## Setup

### 1. Install Dependencies

```bash
bun install
```

### 2. Configure Environment

Create a `.env` file:

```env
# API Key for authentication (required for production)
# Generate: openssl rand -hex 32
API_KEY=your_secure_api_key_here

# Wispr Flow SDK credentials
WISPR_EMAIL=your_email
WISPR_PASSWORD=your_password
SUPABASE_URL=https://your-supabase-url.supabase.co
SUPABASE_ANON_KEY=your_supabase_anon_key
BASETEN_URL=https://your-baseten-url.api.baseten.co
BASETEN_API_KEY=your_baseten_api_key
```

### 3. Run the Server

```bash
# Development (no API key required)
bun run server.ts

# Production
API_KEY=your_key bun run server.ts
```

Server starts on `http://localhost:3002`

## API Endpoints

| Endpoint      | Method | Auth | Description      |
| ------------- | ------ | ---- | ---------------- |
| `/health`     | GET    | No   | Health check     |
| `/transcribe` | POST   | Yes  | Transcribe audio |

### POST /transcribe

**Request:**

```json
{
  "audio": "base64_encoded_wav_audio"
}
```

**Response:**

```json
{
  "status": "success",
  "text": "transcribed text",
  "raw": {
    "asr_text": "...",
    "llm_text": "...",
    "pipeline_text": "...",
    "status": "...",
    "total_time": 1234
  }
}
```

## Authentication

For production, send the API key via one of:

- Header: `X-API-Key: your_api_key`
- Header: `Authorization: Bearer your_api_key`
- Query param: `?api_key=your_api_key`

When `API_KEY` is not set, authentication is disabled (local development mode).

## Environment Variables

| Variable            | Required   | Description                 |
| ------------------- | ---------- | --------------------------- |
| `PORT`              | No         | Server port (default: 3002) |
| `API_KEY`           | Production | API key for authentication  |
| `WISPR_EMAIL`       | Yes        | Wispr Flow account email    |
| `WISPR_PASSWORD`    | Yes        | Wispr Flow account password |
| `SUPABASE_URL`      | Yes        | Supabase project URL        |
| `SUPABASE_ANON_KEY` | Yes        | Supabase anonymous key      |
| `BASETEN_URL`       | Yes        | Baseten API URL             |
| `BASETEN_API_KEY`   | Yes        | Baseten API key             |

## Deployment

The server can be deployed to any platform that supports Bun:

- Railway
- Fly.io
- Render
- VPS with Bun installed

Make sure to set all environment variables in your deployment platform.
