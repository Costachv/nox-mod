package com.nox;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Ponte servidor -> cliente (só funciona em singleplayer, onde os dois rodam no mesmo processo). */
public class Bridge {
    public static final Queue<String> Q = new ConcurrentLinkedQueue<>();
}
