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
    oneWay(current.parsed >= "26.1",
        "\\bworld\\.random\\b" to "world.getRandom()",
    )
    // 26.1: the player's chat message method is gone; 1.21.11's displayClientMessage(text, false) made exactly
    // this call. From 26.2 the chat listener belongs to Gui.
    oneWay(current.parsed >= "26.1" && current.parsed < "26.2",
        "\\bclient\\.player\\.displayClientMessage\\(" to "client.getChatListener().handleSystemMessage(",
    )
    oneWay(current.parsed >= "26.2",
        "\\bclient\\.player\\.displayClientMessage\\(" to "client.gui.chatListener().handleSystemMessage(",
    )
    // 26.2: the open screen moved from Minecraft to Gui, the HUD and chat to Gui's Hud, and the level renderer's
    // per-level part to LevelExtractor. "client" and "c" are always a Minecraft in this code.
    oneWay(current.parsed >= "26.2",
        "(?<![.\\w])(client|c)\\.screen\\b" to "$1.gui.screen()",
        "\\bMinecraft\\.getInstance\\(\\)\\.screen\\b" to "Minecraft.getInstance().gui.screen()",
        "\\b(client|c|this\\.minecraft)\\.setScreen\\(" to "$1.gui.setScreen(",
        "\\bc\\.getOverlay\\(\\)" to "c.gui.overlay()",
        "\\bc\\.getMainRenderTarget\\(\\)" to "c.gameRenderer.mainRenderTarget()",
        "\\.getMainCamera\\(\\)" to ".mainCamera()",
        "\\b(client|c)\\.levelRenderer\\b" to "$1.levelExtractor",
        "\\bclient\\.options\\.hideGui\\b" to "client.gui.hud.isHidden()",
        "\\.gui\\.getChat\\(\\)" to ".gui.hud.getChat()",
        "\\bEntityType\\.(PLAYER|ARMOR_STAND|ZOMBIE)\\b" to "net.minecraft.world.entity.EntityTypes.$1",
    )
}
