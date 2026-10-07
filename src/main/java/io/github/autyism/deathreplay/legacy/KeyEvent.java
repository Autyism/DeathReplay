package io.github.autyism.deathreplay.legacy;

//? if <1.21.9 {
/*/^*
 * Before 1.21.9 screens got key presses as three numbers. This is the record 1.21.9 hands them
 * over as, so the mod's screens read the same on every version ({@link LegacyInputScreen}).
 ^/
public record KeyEvent(int key, int scancode, int modifiers) {
}
*///?}
