# Construct

Construct is a Minecraft Fabric mod that generates `.schem` files from text prompts using Gemini AI.

The idea is simple: instead of building everything block by block, you can describe a structure in plain text and let the mod generate a schematic for you. For example, a prompt like "medieval stone tower" can produce a build that you can later place in your world.

This project was created to make experimenting with builds faster and more fun, especially when prototyping ideas.

---

## How It Works

The mod adds a set of server commands that allow players to send prompts to the Gemini AI API.  
The server processes the request and returns the generated schematic once the job is finished.

Generation happens asynchronously, so the server does not freeze while the request is being processed. While waiting, players receive progress feedback so they know the job is still running.

Each player also has access to a small personal history of previous generation requests.

---

## Requirements

Before running the mod, make sure you have:

- **Java 21 or newer**
- **Gradle** (or use the included Gradle wrapper)
- A **Gemini AI API key**

---

## Build

To compile the mod, run:

```bash
./gradlew build
````

After the build finishes, the compiled `.jar` file will be available in:

```
build/libs/
```

---

## API Key Setup

Inside the game, run:

```
/constructapikey
```

This opens the screen where you can enter your Gemini AI API key.

The key will be saved locally in the client configuration file:

```
construct.json
```

If you ever want to remove the stored key, run:

```
/constructapikey clear
```

---

## Commands

### `/construct <building>`

Starts a generation request using the provided prompt.

Example:

```
/construct small japanese shrine
```

---

### `/history [limit]`

Displays recent generation requests made by the player.

You can optionally provide a number to limit how many results are shown.

Example:

```
/history 5
```

---

### `/constructapikey`

Opens the API key configuration screen on the client.

---

## Future Ideas

Some possible improvements for the project include:

* schematic preview before download
* support for multiple generation styles
* integration with WorldEdit
* generation of larger or multi-part structures

Contributions, ideas, and feedback are always welcome.