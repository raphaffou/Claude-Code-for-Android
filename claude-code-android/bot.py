import os
import asyncio
import logging
import subprocess
from dotenv import load_dotenv
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, MessageHandler, filters, ContextTypes
from telegram.constants import ChatAction

load_dotenv()

TELEGRAM_TOKEN = os.environ["TELEGRAM_TOKEN"]
ALLOWED_USER_ID = int(os.environ["ALLOWED_USER_ID"])
CLAUDE_BIN = os.path.expanduser("~/.nvm/versions/node/v22.22.3/bin/claude")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# Conversation history per user: list of {"role": "user"|"assistant", "content": str}
histories: dict[int, list[dict]] = {}


def get_history(user_id: int) -> list[dict]:
    return histories.setdefault(user_id, [])


def is_allowed(update: Update) -> bool:
    return update.effective_user.id == ALLOWED_USER_ID


def build_prompt(history: list[dict], new_message: str) -> str:
    """Format conversation history + new message as a single prompt."""
    parts = []
    for turn in history:
        role = "User" if turn["role"] == "user" else "Assistant"
        parts.append(f"{role}: {turn['content']}")
    parts.append(f"User: {new_message}")
    parts.append("Assistant:")
    return "\n\n".join(parts)


async def call_claude(prompt: str) -> str:
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(
        None,
        lambda: subprocess.run(
            [CLAUDE_BIN, "-p", prompt],
            capture_output=True,
            text=True,
            timeout=120,
        ),
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "claude exited with error")
    return result.stdout.strip()


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_allowed(update):
        return
    await update.message.reply_text("Salut ! Je suis Claude. Envoie-moi un message.")


async def cmd_clear(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_allowed(update):
        return
    histories.pop(update.effective_user.id, None)
    await update.message.reply_text("Conversation effacée.")


async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not is_allowed(update):
        return

    user_id = update.effective_user.id
    text = update.message.text
    history = get_history(user_id)

    await context.bot.send_chat_action(chat_id=update.effective_chat.id, action=ChatAction.TYPING)

    prompt = build_prompt(history, text)

    try:
        reply = await call_claude(prompt)
        history.append({"role": "user", "content": text})
        history.append({"role": "assistant", "content": reply})

        for chunk in [reply[i:i+4096] for i in range(0, len(reply), 4096)]:
            await update.message.reply_text(chunk)

    except Exception as e:
        log.error("Claude error: %s", e)
        await update.message.reply_text(f"Erreur : {e}")


def main():
    app = ApplicationBuilder().token(TELEGRAM_TOKEN).build()
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("clear", cmd_clear))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))

    log.info("Bot démarré, polling...")
    app.run_polling()


if __name__ == "__main__":
    main()
