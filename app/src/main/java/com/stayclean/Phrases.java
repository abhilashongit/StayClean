package com.stayclean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Short, kind lines under STAY CLEAN. Shuffle-bag: every line is used once before any repeats. */
public final class Phrases {
    public static final String[] FOOD = {
            "Have something light.",
            "Sleep is the better order.",
            "Hunger fades. Rest stays.",
            "Water first. Then sleep.",
            "You've done this before.",
            "Not tonight. You've got this.",
            "Cravings pass in minutes.",
            "Be kind to your body.",
            "A small win, quietly.",
            "Close your eyes instead.",
            "Morning-you has plans.",
            "Fruit, curd, or a glass of milk.",
            "This urge is a wave. Let it pass.",
            "Choose rest.",
            "Future you will say thanks.",
            "Proud of you for pausing.",
            "Eat what's already home.",
            "Late meals can wait for the sun.",
            "Lighter tonight, lighter morning.",
            "Keep the streak going.",
            "You're building something good.",
            "Just a glass of water.",
            "Breathe. It will pass.",
            "Tomorrow tastes better.",
            "Quiet nights, clear mornings."
    };

    public static final String[] ALL = FOOD;

    public static final String[] SOCIAL_LINES = {
            "Put the phone down.",
            "The feed can wait.",
            "Sleep beats the scroll.",
            "Nothing new that can't wait.",
            "Rest your eyes.",
            "Be here, not there.",
            "Let your mind go quiet.",
            "Dark screen, deep sleep.",
            "Tomorrow's feed is the same feed.",
            "Close the day gently.",
            "You don't owe anyone a reply.",
            "Enough for today.",
            "Stillness is a good feed.",
            "Your bed is calling."
    };

    /** Food lines (Swiggy / Zomato). */
    public static final Phrases DEFAULT = new Phrases(FOOD, new Random());
    /** Instagram / X night lock lines. */
    public static final Phrases SOCIAL = new Phrases(SOCIAL_LINES, new Random());

    private final String[] items;
    private final Random rnd;
    private final List<Integer> bag = new ArrayList<>();
    private int last = -1;

    public Phrases(Random rnd) { this(FOOD, rnd); }
    public Phrases(String[] items, Random rnd) { this.items = items; this.rnd = rnd; }

    public synchronized String next() {
        if (bag.isEmpty()) {
            for (int i = 0; i < items.length; i++) bag.add(i);
            Collections.shuffle(bag, rnd);
            if (bag.size() > 1 && bag.get(bag.size() - 1) == last) Collections.swap(bag, 0, bag.size() - 1);
        }
        last = bag.remove(bag.size() - 1);
        return items[last];
    }
}
