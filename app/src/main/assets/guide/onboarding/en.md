<!-- tellev onboarding guide. Format: `## ` starts a page; `<!-- where -->` is followed by entry points,
     `<!-- steps -->` by usage steps. Translate the prose only — never touch the `## ` lines or any
     `<!-- -->` marker in this file. -->

## Step 1: Set up a model service

Tellev does not include a model. Connect your own API service — an OpenAI-compatible endpoint or a relay both work.

<!-- where -->
- Settings → Model services → "Manage model service configs"

<!-- steps -->
1. On the Settings tab, open "Manage model service configs" under "Model services" and add a connection: API URL, key and model name.
2. Generation parameters (presets) live in the Settings tab as well — that is where you tune sampling or the context limit.
3. Send a message on the Chat tab: if you get a reply, you are set up.

## Step 2: Get your first character

Three ways in: import an existing card, write one yourself, or have the AI draft one for you.

<!-- where -->
- The Characters tab at the bottom
- Characters tab, top right: "Import character card"
- Characters tab: "New Character" at the bottom, "AI Create" in the top bar

<!-- steps -->
1. Open the Characters tab and tap "Import character card" in the top right to pick a PNG or JSON card from your files; SillyTavern cards work as they are.
2. To write your own, tap "New Character" and fill in the name, persona and first message.
3. Not sure where to start? Tap "AI Create" in the top bar, describe the idea in one sentence and edit the result.

## Step 3: Start chatting

The Chat tab is the main screen — pick a character, open a session and send messages here.

<!-- where -->
- The Chat tab at the bottom

<!-- steps -->
1. Open the Chat tab and tap a character in the list — that opens a new session.
2. Type in the box at the bottom and hit send to start the conversation.
3. "Sessions" in the top bar creates or deletes sessions; "More options → Switch persona" changes who you are.
4. "More options" also holds the chat background, persona switching and long-term memory.

## Step 4: Worth trying

Beyond chatting, a few features are worth setting up early.

<!-- where -->
- Settings → Image gen (engine & model)
- Extensions → Long-term memory
- The World Books tab at the bottom
- Settings → theme, language

<!-- steps -->
1. Image generation: configure an engine under "Settings → Image gen (engine & model)" and a generate button appears in the chat input bar; generated images land in the chat gallery.
2. Long-term memory: turn it on from the Extensions tab so long conversations stop forgetting earlier details.
3. World books: give a character a setting document and the model picks up those details automatically.
4. Look and language: switch the accent colour, bubble opacity and interface language from Settings.
