-- V79: Rewrite the unlock-only achievement-reward copy so it reads like a
-- person wrote it, the second half of the ENG-3 unslop pass.
--
-- V78 handled the shop-visible products. The unlock-only rows (emote packs
-- V25-V40, earned titles V35 / V41 / V42 / V44 / V45, the founding badge V43)
-- were left untouched there because each description encodes the achievement
-- criteria that earns it, and rewriting risked altering a factual claim.
-- This pass makes the same two edits V78 did while preserving every criterion
-- verbatim:
--   1. em dashes become ordinary punctuation;
--   2. the repeated "Send from the in-game emote tray." tail is dropped (the
--      emote tray is the UI affordance, not the copy, the same reasoning that
--      dropped "Equip from your items." in V78). The single in-game-tray
--      mention is folded into the roster sentence so the player still learns
--      where the emotes live.
--
-- The leading "Unlocked by ..." / "Granted to ..." criterion clause of every
-- row is kept word-for-word so the earn condition can never drift. Append-only
-- UPDATEs over the existing rows so dev Flyway checksums stay intact; the rows
-- surface via readById (player card / inventory), not the shop list.
--
-- Prod carries the same rows and must end up with identical copy; that mirror
-- is a reviewed, credentialed write and stays with the human (ENG-3).

-- Emote packs ----------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Unlocked by busting five opponents. Sends 🪦 ⚰️ 👻 🥀 from the in-game emote tray, fitting last rites for the seat that just folded.","es":"Desbloqueado al eliminar a cinco oponentes. Envía 🪦 ⚰️ 👻 🥀 desde la bandeja de emotes, últimos ritos para el asiento que se acaba de retirar."}'::jsonb
WHERE id = 'emotes_eliminator';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by tripling your starting stack in a single hand. Sends 💸 💎 🤑 📈 from the in-game emote tray, for the seat that just printed money.","es":"Desbloqueado al triplicar tu pila inicial en una sola mano. Envía 💸 💎 🤑 📈 desde la bandeja de emotes, para el asiento que acaba de imprimir dinero."}'::jsonb
WHERE id = 'emotes_baller';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by surviving 100 hands in a row without busting. Sends 🛡️ 🧱 🗿 🦾 from the in-game emote tray, for the seat that refused to crumble.","es":"Desbloqueado al sobrevivir 100 manos seguidas sin perder toda tu pila. Envía 🛡️ 🧱 🗿 🦾 desde la bandeja de emotes, para el asiento que se negó a derrumbarse."}'::jsonb
WHERE id = 'emotes_iron_stack';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by winning 10 pots without ever reaching showdown. Sends 🪄 🎩 😏 🤫 from the in-game emote tray, for the seat that talked everyone else out of their chips.","es":"Desbloqueado al ganar 10 botes sin llegar a la confrontación. Envía 🪄 🎩 😏 🤫 desde la bandeja de emotes, para el asiento que convenció al resto de retirarse."}'::jsonb
WHERE id = 'emotes_convincer';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by making 25 hindsight-correct folds, hands you would have lost at showdown. Sends 🧘 🦉 👁️ 🪞 from the in-game emote tray, for the seat that read the table and quietly stayed alive.","es":"Desbloqueado al hacer 25 retiradas correctas en retrospectiva, manos que habrías perdido en la confrontación. Envía 🧘 🦉 👁️ 🪞 desde la bandeja de emotes, para el asiento que leyó la mesa y se mantuvo vivo en silencio."}'::jsonb
WHERE id = 'emotes_disciplined';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by playing 1,000 hands, the long-tail commitment most players never see. Sends ☕ ⛏️ 🛠️ ⌛ from the in-game emote tray, for the seat that just keeps chipping away.","es":"Desbloqueado al jugar 1.000 manos, el compromiso a largo plazo que la mayoría de los jugadores nunca ve. Envía ☕ ⛏️ 🛠️ ⌛ desde la bandeja de emotes, para el asiento que sigue trabajando sin parar."}'::jsonb
WHERE id = 'emotes_grinder';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by finishing a hand with 2× the chips you started it with, the moment everyone at the table notices. Sends 🚀 ⏫ 🎯 💰 from the in-game emote tray, for the seat that just called their shot.","es":"Desbloqueado al terminar una mano con el doble de fichas con las que la empezaste, el momento en que toda la mesa se da cuenta. Envía 🚀 ⏫ 🎯 💰 desde la bandeja de emotes, para el asiento que acaba de cantar su jugada."}'::jsonb
WHERE id = 'emotes_doubler';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by winning 10 hands on the Challenging difficulty, where the bots play tighter, raise harder, and bluff less. Sends ♟️ 🦅 🥷 🏹 from the in-game emote tray, for the seat that didn''t get lucky, just read the table.","es":"Desbloqueado al ganar 10 manos en la dificultad Desafiante, donde los bots juegan más cerrado, suben más fuerte y van menos de farol. Envía ♟️ 🦅 🥷 🏹 desde la bandeja de emotes, para el asiento que no tuvo suerte, sino que leyó la mesa."}'::jsonb
WHERE id = 'emotes_tactician';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by beating Jane 10 times. The gatekeeper folds for a reason, and you read it. Sends 🔍 📋 🤓 ☝️ from the in-game emote tray, for the seat that had the spreadsheet on you.","es":"Desbloqueado al ganarle a Jane 10 veces. El portero se retira por una razón, y la leíste. Envía 🔍 📋 🤓 ☝️ desde la bandeja de emotes, para el asiento que tenía la hoja de cálculo sobre ti."}'::jsonb
WHERE id = 'emotes_inspector';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by beating David 10 times. The bluffer ran the script and you outshowed the show. Sends 🎤 ✨ 👏 🎬 from the in-game emote tray, for the seat that took the mic.","es":"Desbloqueado al ganarle a David 10 veces. El farolero recitó el guion y tú robaste el espectáculo. Envía 🎤 ✨ 👏 🎬 desde la bandeja de emotes, para el asiento que tomó el micrófono."}'::jsonb
WHERE id = 'emotes_showstopper';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by beating Gina 10 times. The fox sets traps and you walked around every one. Sends 💡 🪤 🕸️ 🔮 from the in-game emote tray, for the seat that saw it coming.","es":"Desbloqueado al ganarle a Gina 10 veces. La zorra tiende trampas y tú esquivaste cada una. Envía 💡 🪤 🕸️ 🔮 desde la bandeja de emotes, para el asiento que lo vio venir."}'::jsonb
WHERE id = 'emotes_outsmarter';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by beating Steve 10 times. The turtle waits forever and you waited longer. Sends 🦥 🐌 🪨 🌅 from the in-game emote tray, for the seat that out-grinded the grinder.","es":"Desbloqueado al ganarle a Steve 10 veces. La tortuga espera para siempre y tú esperaste más. Envía 🦥 🐌 🪨 🌅 desde la bandeja de emotes, para el asiento que aguantó más que el aguantador."}'::jsonb
WHERE id = 'emotes_marathoner';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by beating Mike 10 times. The maniac jams everything and you rode out the storm. Sends 🦁 🎪 🤹 🪅 from the in-game emote tray, for the seat that tamed the chaos.","es":"Desbloqueado al ganarle a Mike 10 veces. El maníaco apuesta todo y tú aguantaste la tormenta. Envía 🦁 🎪 🤹 🪅 desde la bandeja de emotes, para el asiento que domó el caos."}'::jsonb
WHERE id = 'emotes_tamer';

-- Earned titles --------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Unlocked by reaching level 25. Shows under your name at the table, the seat that has been around a while.","es":"Desbloqueado al alcanzar el nivel 25. Aparece bajo tu nombre en la mesa, el asiento que lleva tiempo aquí."}'::jsonb
WHERE id = 'title_felt_veteran';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by showing a royal flush at showdown, the rarest hand in poker. Shows under your name at the table.","es":"Desbloqueado al mostrar una escalera real en la confrontación, la mano más rara en el póker. Aparece bajo tu nombre en la mesa."}'::jsonb
WHERE id = 'title_royalty';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by showing a straight flush at showdown, five suited cards in sequence. Shows under your name at the table.","es":"Desbloqueado al mostrar una escalera de color en la confrontación, cinco cartas del mismo palo en secuencia. Aparece bajo tu nombre en la mesa."}'::jsonb
WHERE id = 'title_suited_run';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by showing a full house at showdown, three of a kind plus a pair. Shows under your name at the table.","es":"Desbloqueado al mostrar un full en la confrontación, tres iguales más una pareja. Aparece bajo tu nombre en la mesa."}'::jsonb
WHERE id = 'title_full_boat';

UPDATE products
SET description_by_locale = '{"en":"Unlocked by showing four of a kind at showdown, four cards of the same rank. Shows under your name at the table.","es":"Desbloqueado al mostrar póker en la confrontación, cuatro cartas del mismo valor. Aparece bajo tu nombre en la mesa."}'::jsonb
WHERE id = 'title_quartet';

-- Founding badge -------------------------------------------------------------

UPDATE products
SET description_by_locale = '{"en":"Granted to the first 1,000 players to find a seat. Equip it from your items to show it at your seat at the table.","es":"Concedida a los primeros 1.000 jugadores en encontrar asiento. Equípala desde tus objetos para mostrarla en tu asiento de la mesa."}'::jsonb
WHERE id = 'badge_founding_member_1000';
