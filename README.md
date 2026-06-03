# VibeChat

VibeChat is a small Paper plugin for Minecraft server chat workflows. It is a learning MVP built around real server needs: local/global chat, private messages, simple moderation, links, mentions, and configurable sounds.

## Features

- Local and global chat channels.
- Private messages with reply support.
- Ignore, mute, unmute, and reload commands.
- Basic anti-spam, repeat-message, bad-word, link, and IP filtering.
- Configurable Discord, Telegram, and site link commands.
- Placeholder-friendly formatting for prefixes and suffixes.
- Optional clickable player names, clickable commands, mentions, and sounds.

## Status

MVP / learning project. The plugin has been built and tested as a small practical server tool, but it is not presented as a finished commercial product.

Known risks:

- Chat moderation rules are simple and may need tuning for a real community.
- Some Paper/Bukkit sound APIs used by the plugin may change in future Minecraft versions.
- The default bad-word list and messages are opinionated and should be reviewed before use.

## Build

Requirements:

- Java 21
- Gradle wrapper included

```powershell
.\gradlew.bat build
```

The built plugin jar will be created in `build/libs/`.

## Configuration

Main configuration files are in `src/main/resources/`:

- `settings.yml`
- `messages.yml`
- `badwords.yml`

Server owners should review all defaults before installing the plugin.

## License

MIT. See `LICENSE`.

