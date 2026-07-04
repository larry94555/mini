package com.example.imini.ext;

import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * USE CASE 4 — contribute a slash command in code.
 *
 * <p>Equivalent to a {@code commands/<name>.md} template file, but shipped in an extension. A command is
 * a prompt template where {@code $ARGS} (or {@code $ARGUMENTS}) is replaced by the text the user typed
 * after the command; the expanded text becomes the prompt the model sees.
 *
 * <p>Here {@code /shout hello world} expands to a prompt asking the model to reply in emphatic uppercase.
 * On-disk {@code commands/shout.md} wins if it also exists.
 */
@Component
public class ShoutCommandExtension implements Extension {

    @Override
    public String name() {
        return "shout-command";
    }

    @Override
    public List<Command> commands(ExtensionContext ctx) {
        Command shout = new Command(
                "shout",
                "Reply to the argument in emphatic UPPERCASE.",
                "Rewrite the following in emphatic, ALL-CAPS shouting, keeping the meaning:\n\n$ARGS");
        return List.of(shout);
    }
}
