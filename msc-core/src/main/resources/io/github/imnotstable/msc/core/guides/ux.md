UX GUIDE: how a server feels polished. Use the standard library the template ships (Gui, ItemBuilder, Sidebar, ParticleEffect, Text, PlayerData) instead of writing these again.

EVERY ACTION GETS FEEDBACK
- Sound + text + (often) particles. A click plays UI_BUTTON_CLICK, a success a level-up or chime, a failure a low note (BLOCK_NOTE_BLOCK_BASS) and a red message saying why and what to do.
- Pick the right channel: chat for things to keep, action bar for live values (mana, cooldowns, combo), title/subtitle for big moments (region entered, boss down, level up), boss bar for progress over time (event timer, objective, boss health), sidebar for the few numbers a player checks often.
- Never spam: one message per action, cooldowns on repeated warnings.

TEXT
- MiniMessage with a consistent style: one prefix for the server, one colour per meaning (gold rewards, red errors, aqua info, gray detail). Gradients only for names and headings.
- Numbers formatted (1,250 Crowns, 3m 20s), symbols for quick reading (★ ⚔ ❤ ⛏ ✦).

SIDEBAR
- 6-10 lines with a title, grouped with blank lines: who you are (rank, class), what you have (currency, level with a progress bar like ▮▮▮▮▯▯), where you are (region), what to do next (current objective). Update on change, not every tick.

MENUS (Gui)
- A menu is a designed screen: border or filler panes in a theme colour, a centred layout, an icon with a custom model per entry, a name and 2-4 lore lines (what it is, what it costs, "Click to ...").
- State shows on the icon: locked entries greyed with the requirement, owned ones glowing (ItemBuilder.glow()), the selected one marked.
- Back and close buttons in the same slots on every page; paged menus for long lists; confirm screens for spending.
- Click sounds, and the menu refreshes in place instead of closing.

WORLD AND MOMENTS
- Holograms (TextDisplay) over NPCs, shops and objectives; particles to mark interactables and paths (ParticleEffect along shapes).
- Big moments get a sequence: title, sound, particles, a short delay, then the reward message.
- First join: a welcome title, a short guided first objective, and a starter kit, not a wall of chat.

CHECK
- player_test: join as a bot, click through every menu and command, read what the player sees. Nothing a player can do should end in silence or a stack trace.
