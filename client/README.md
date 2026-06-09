# Test Client

Simple interactive client for the `/ask` server (same JSON shape as `claude-llamacpp-test/run.sh`).

## Setup

From this directory:

```bash
cd /home/larry/test-client
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

`prompt_toolkit` enables multi-line prompts:

- **Enter** — send the prompt
- **Alt+Enter** or **Shift+Enter** — new line (Shift+Enter works in VS Code / Windows Terminal)

If you skip `prompt_toolkit`, the client still works with a fallback:

- blank line submits
- end a line with `\` to continue on the next line

## Run

Make sure the server is running on Windows at port `8080`, then:

```bash
cd /home/larry/test-client
source .venv/bin/activate
python3 client.py
```

Or without a venv (after installing `prompt_toolkit` globally):

```bash
cd /home/larry/test-client
python3 client.py
```

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `WIN_HOST` | WSL default gateway | Windows host IP from WSL |
| `PORT` | `8080` | Server port |

Example:

```bash
WIN_HOST=172.28.32.1 PORT=8080 python3 client.py
```

## Quit

Press **Ctrl+C** at any prompt.