#!/usr/bin/env python3
"""Interactive client for the /ask endpoint (Windows host from WSL)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request


def windows_host() -> str:
    if host := os.environ.get("WIN_HOST"):
        return host
    try:
        result = subprocess.run(
            ["ip", "route", "show", "default"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.split()[2]
    except (subprocess.CalledProcessError, IndexError):
        return "172.28.32.1"


def ask_server(question: str, host: str, port: str) -> str:
    url = f"http://{host}:{port}/ask"
    payload = json.dumps({"question": question}).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach {url}: {exc.reason}") from exc

    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        return body

    if isinstance(data, dict):
        for key in ("answer", "response", "result", "text"):
            if key in data and data[key] is not None:
                value = data[key]
                return value if isinstance(value, str) else json.dumps(value, indent=2)
        return json.dumps(data, indent=2)
    return str(data)


def read_prompt_simple() -> str:
    print("Prompt (blank line to submit; end a line with \\ to continue):")
    lines: list[str] = []
    while True:
        try:
            line = input("prompt> " if not lines else "     > ")
        except EOFError:
            break
        if not lines and not line:
            return ""
        if not line and lines:
            break
        if line.endswith("\\"):
            lines.append(line[:-1])
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def read_prompt_rich() -> str:
    from prompt_toolkit import PromptSession
    from prompt_toolkit.key_binding import KeyBindings

    bindings = KeyBindings()

    @bindings.add("enter")
    def submit(event) -> None:
        event.current_buffer.validate_and_handle()

    @bindings.add("escape", "enter")
    def insert_newline(event) -> None:
        event.current_buffer.insert_text("\n")

    session = PromptSession(multiline=True, key_bindings=bindings)
    print("Prompt (Enter to submit; Alt+Enter or Shift+Enter for a new line):")
    text = session.prompt("prompt> ")
    return text.strip()


def read_prompt() -> str:
    if not sys.stdin.isatty():
        return sys.stdin.read().strip()
    try:
        return read_prompt_rich()
    except ImportError:
        print("(Install prompt_toolkit for Shift+Enter support; using fallback input.)")
        return read_prompt_simple()


def main() -> int:
    host = windows_host()
    port = os.environ.get("PORT", "8080")
    print(f"Connected to http://{host}:{port}/ask")
    print("Press Ctrl+C to quit.\n")

    while True:
        try:
            prompt = read_prompt()
        except (KeyboardInterrupt, EOFError):
            print("\nBye.")
            return 0

        if not prompt:
            print("Empty prompt, try again.\n")
            continue

        try:
            answer = ask_server(prompt, host, port)
        except RuntimeError as exc:
            print(f"Error: {exc}\n")
            continue

        print(f"\nResponse: {answer}\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())