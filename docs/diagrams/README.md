# docs/diagrams

The architecture diagrams, one Mermaid source file per section of
[`../ARCHITECTURE.md`](../ARCHITECTURE.md). These files are the source of
truth; the prose explaining each one lives in that document.

| File | Section | What it answers |
|---|---|---|
| `01-layered-view.mmd` | §1 | Where does everything sit? |
| `02-bootstrap-sequence.mmd` | §2 | What happens on a fresh box, in order? |
| `03-request-flow.mmd` | §3 | How does a phone reach an app? |
| `04-control-plane.mmd` | §4 | What drives what? |

## Viewing them

**In your editor.** VS Code with the *Markdown Preview Mermaid Support* or
*Mermaid Editor* extension previews a `.mmd` file directly. JetBrains IDEs
have Mermaid support built into their Markdown plugin.

**In a browser.** Paste the file contents into <https://mermaid.live>,
which also gives you PNG and SVG export.

**On the command line.** `mmdc` renders to SVG or PNG:

```bash
npx --yes @mermaid-js/mermaid-cli -i docs/diagrams/04-control-plane.mmd -o /tmp/control-plane.svg
open /tmp/control-plane.svg
```

One caveat, found the hard way on an Apple Silicon Mac in August 2026:
`mmdc` drives headless Chrome through Puppeteer, and the Chrome it
downloads gets SIGKILLed by Gatekeeper (`com.apple.provenance`), so the
command above fails with "Failed to launch the browser process". Nothing
is wrong with the diagram when that happens. Use an editor extension or
mermaid.live instead, or point `mmdc` at a Chrome you already trust with
a Puppeteer config file:

```bash
echo '{"executablePath": "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}' > /tmp/pptr.json
npx --yes @mermaid-js/mermaid-cli -p /tmp/pptr.json -i docs/diagrams/04-control-plane.mmd -o /tmp/out.svg
```

## A note on why these are not embedded

They used to be fenced ```mermaid blocks inside `ARCHITECTURE.md`, which
GitHub renders inline. Splitting them out trades that inline rendering for
files you can open, edit and export on their own. If you want both, the
honest way is to keep these `.mmd` files as the source and generate SVGs
from them into the doc — not to paste the source into two places, which
only guarantees the copies drift.

## Editing

Validate after any change; a diagram that fails to parse renders as
nothing at all, and the failure is silent in most viewers. mermaid.live
reports parse errors as you type, which is the quickest loop.
