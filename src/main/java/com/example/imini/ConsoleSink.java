package com.example.imini;

/** Sink for the blocking endpoints / CLI: tokens stream to stdout, logs print as lines. */
public class ConsoleSink implements RunSink {
    @Override public void token(String text) { System.out.print(text); }
    @Override public void log(String line) { System.out.println(line); }
}
