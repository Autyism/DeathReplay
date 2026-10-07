plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

// src/ is always kept in the 1.21.11 state; never switch the active version.
// The renames below go one way: they apply to the versions their condition is true for, and their
// reverse pattern "(?!)" matches nothing, so the 1.21.11 source is never touched by them.
// Only names that mean one thing in this code are renamed this way; everything else uses //? conditions.
val never = "(?!)"

stonecutter parameters {
    fun oneWay(condition: Boolean, vararg renames: Pair<String, String>) {
        for ((from, to) in renames) {
            replacements.regex(condition) { replace(from, to, never, to) }
        }
    }

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
    }

    // 26.1: GUI drawing became an "extract" pass (same arguments). In this code these calls only ever
    // go to GuiGraphics, and only screens override render / renderBackground.
    oneWay(current.parsed >= "26.1",
        "\\bGuiGraphics\\b" to "GuiGraphicsExtractor",
        "\\.drawString\\(" to ".text(",
        "\\.drawCenteredString\\(" to ".centeredText(",
        "\\bpublic void render\\((?=GuiGraphics\\b)" to "public void extractRenderState(",
        "\\bpublic void renderBackground\\((?=GuiGraphics\\b)" to "public void extractBackground(",
        "\\bsuper\\.render\\(context, " to "super.extractRenderState(context, ",
    )
    // 26.1: Fabric API renames
    oneWay(current.parsed >= "26.1",
        "\\bkeybinding\\.v1\\.KeyBindingHelper\\b" to "keymapping.v1.KeyMappingHelper",
        "\\bKeyBindingHelper\\.registerKeyBinding\\(" to "KeyMappingHelper.registerKeyMapping(",
        "\\bKeyBindingHelper\\.getBoundKeyOf\\(" to "KeyMappingHelper.getBoundKeyOf(",
        "\\bScreens\\.getButtons\\(" to "Screens.getWidgets(",
        "\\bScreens\\.getTextRenderer\\(" to "Screens.getFont(",
        "\\bScreenEvents\\.afterRender\\(" to "ScreenEvents.afterExtract(",
    )
    // 26.1: a level's random source is no longer public ("world" is always a ClientLevel in this code).
    // The player's chat message method is gone; 1.21.11's displayClientMessage(text, false) made exactly this call.
    oneWay(current.parsed >= "26.1",
        "\\bworld\\.random\\b" to "world.getRandom()",
        "\\bclient\\.player\\.displayClientMessage\\(" to "client.getChatListener().handleSystemMessage(",
    )
}
