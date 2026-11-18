# VynoxPlus Telegram Scheduler

Cloudflare Worker that posts to a Telegram channel twice daily.

## Schedule

Cloudflare Cron Triggers use UTC:
- `30 6 * * *` = 10:00 Iran time (UTC+03:30)
- `30 16 * * *` = 20:00 Iran time (UTC+03:30)

## Cloudflare variables and secrets

Set these in the Worker settings. Never commit bot credentials to GitHub.

- Secret `BOT_TOKEN`: token from BotFather.
- Variable or secret `CHANNEL_ID`: public channel username such as `@yourchannel`, or the numeric ID for a private channel.
- Secret `ADMIN_KEY`: a long random value used only for the protected test endpoint.

## Health check

Open the Worker URL with `/health`. It reports whether the required settings exist, without revealing their values.

## Send a test post

Send an HTTP POST request to `/test` with header:

`Authorization: Bearer YOUR_ADMIN_KEY`

The Worker posts a test message to the configured channel.

## Telegram setup

Add the bot as a channel administrator and enable **Post Messages**. The bot does not need permission to read channel messages.

<!-- Cloudflare Git integration deployment trigger: 2026-11-18 -->
