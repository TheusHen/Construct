# Construct

Construct is a Minecraft Fabric mod that generates `.schem` data from text prompts using Hack Club AI.

## What It Does

- Provides the `/construct <building>` server command
- Sends AI requests asynchronously
- Shows progress feedback while requests are running
- Stores a short per-user history via `/history`
- Supports API key setup from the client with `/constructapikey`

## Requirements

- Java 21+
- Gradle (or use the included wrapper)
- A valid Hack Club AI API key

## Quick Start

```bash
./gradlew build
```

Set your API key in game using `/constructapikey`, then run:

```text
/construct <your building prompt>
```

## Commands

- `/construct <building>`: start a generation request
- `/history [limit]`: show recent jobs for the player
- `/constructapikey`: open API key screen on client
- `/constructapikey clear`: clear stored key

## Notes

- API keys are stored in the client config file (`construct.json`).
- Server-side fallback key resolution is supported via runtime properties/env vars.
