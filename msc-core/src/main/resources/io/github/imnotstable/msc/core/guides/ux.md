UX GUIDE: how a server feels polished. The standard library the template ships (Gui, ItemBuilder, Sidebar, ParticleEffect, Text, PlayerData) does the plumbing; every look, sound and line of text is yours, designed for this server. It has no stock themes, sounds or messages.

EVERY ACTION GETS FEEDBACK
- Sound + text + (often) particles. Choose one sound per meaning for this server (click, success, failure, reward) and keep to it; a failure says why and what to do.
- Pick the right channel: chat for things to keep, action bar for live values (mana, cooldowns, combo), title/subtitle for big moments (region entered, boss down, level up), boss bar for progress over time (event timer, objective, boss health), sidebar for the few numbers a player checks often.
- Never spam: one message per action, cooldowns on repeated warnings.

TEXT
- MiniMessage with a style you design for this server: its own prefix, one colour per meaning, used the same everywhere. Gradients only for names and headings.
- Numbers formatted (Format: thousands, durations), symbols that fit the server's theme for quick reading.

SIDEBAR
- A few lines with a title, grouped: who you are, what you have, where you are, what to do next, in this game's terms. Update on change, not every tick.

MENUS (Gui)
- A menu is a screen you design: your own Gui.Theme (border, filler, click sound), a deliberate layout, an icon per entry (your own textures and models), a name and a few lore lines (what it is, what it costs, what a click does).
- State shows on the icon: locked entries greyed with the requirement, owned ones glowing (ItemBuilder.glow()), the selected one marked.
- Back and close buttons in the same slots on every page; paged menus (Gui.Nav with your buttons) for long lists; a confirm screen you lay out for spending.
- Click sounds, and the menu refreshes in place instead of closing.

WORLD AND MOMENTS
- Holograms (TextDisplay) over NPCs, shops and objectives; particles to mark interactables and paths (ParticleEffect along shapes).
- Big moments get a sequence: title, sound, particles, a short delay, then the reward message.
- First join: a welcome title, a short guided first objective, and a starter kit, not a wall of chat.

CHECK
- player_test: join as a bot, click through every menu and command, read what the player sees. Nothing a player can do should end in silence or a stack trace.
