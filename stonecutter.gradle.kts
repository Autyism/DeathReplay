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

    // Before 1.21.11: some classes and getters had other names, and a cycle button got its first value separately.
    // ".identifier()" is only ever called on a ResourceKey in this code.
    oneWay(current.parsed < "1.21.11",
        "\\bimport net\\.minecraft\\.util\\.Util;" to "import net.minecraft.Util;",
        "\\bnet\\.minecraft\\.world\\.level\\.gamerules\\.GameRules\\b" to "net.minecraft.world.level.GameRules",
        "\\.identifier\\(\\)" to ".location()",
        "\\bCycleButton\\.builder\\(([\\w:]+), ([\\w.]+)\\)" to "CycleButton.builder($1).withInitialValue($2)",
        "\\bcamera\\.forwardVector\\(\\)" to "camera.getLookVector()",
        // self-test only: game rules had camel-case names
        "\\bimmediate_respawn\\b" to "doImmediateRespawn",
    )
    // Before 1.21.9: screens got key presses and clicks as plain numbers. The two screens that read input
    // extend a small stand-in that turns them into the newer records (package legacy). The window handle and
    // a player's profile had getters, there was no "invert mouse X" option, and riding had no event switch.
    oneWay(current.parsed < "1.21.9",
        "\\bimport net\\.minecraft\\.client\\.input\\.(KeyEvent|MouseButtonEvent|MouseButtonInfo);" to "import io.github.autyism.deathreplay.legacy.$1;",
        "\\bclass (DeathSpectateScreen|ReplayScreen) extends Screen\\b" to "class $1 extends io.github.autyism.deathreplay.legacy.LegacyInputScreen",
        "\\.matches\\(input\\)" to ".matches(input.key(), input.scancode())",
        "\\.getWindow\\(\\)\\.handle\\(\\)" to ".getWindow().getWindow()",
        "\\boptions\\.invertMouseX\\(\\)\\.get\\(\\)" to "false",
        "\\boptions\\.invertMouseY\\(\\)" to "options.invertYMouse()",
        "\\b(getGameProfile|profile)\\(\\)\\.name\\(\\)" to "$1().getName()",
        "\\b(getGameProfile|profile)\\(\\)\\.id\\(\\)" to "$1().getId()",
        "\\.startRiding\\(([\\w.]+), true, false\\)" to ".startRiding($1, true)",
    )
    // Before 1.21.6: no ready-made way to find a point of the world on the screen (legacy.ScreenProjection does
    // the same), some getters had other names, and a flat world's sky was asked for differently.
    // Self-test only: screenshots had no size option.
    oneWay(current.parsed < "1.21.6",
        "\\bclient\\.gameRenderer\\.projectPointToScreen\\(" to "io.github.autyism.deathreplay.legacy.ScreenProjection.projectPointToScreen(client, ",
        "\\.getCurrentVersion\\(\\)\\.name\\(\\)" to ".getCurrentVersion().getName()",
        "\\.getCurrentVersion\\(\\)\\.dataVersion\\(\\)\\.version\\(\\)" to ".getCurrentVersion().getDataVersion().getVersion()",
        "\\bcamera\\.position\\(\\)" to "camera.getPosition()",
        "\\.getMainCamera\\(\\)\\.position\\(\\)" to ".getMainCamera().getPosition()",
        "\\.voidDarknessOnsetRange\\(\\) == 1\\.0F" to ".getClearColorScale() == 1.0F",
        "\\bc\\.getMainRenderTarget\\(\\), 1," to "c.getMainRenderTarget(),",
    )
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
    // 26.3: input goes through SDL. Keyboard keys are SDL scancodes, mouse buttons are numbered from 1, and
    // there is no GLFW. InputConstants names the same physical keys on every version, so saved keys still match.
    oneWay(current.parsed >= "26.3",
        "\\bGLFW\\.GLFW_KEY_ENTER\\b" to "com.mojang.blaze3d.platform.InputConstants.KEY_RETURN",
        "\\bGLFW\\.GLFW_KEY_KP_ENTER\\b" to "com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER",
        "\\bGLFW\\.GLFW_KEY_(ESCAPE|LEFT|RIGHT|R|F6)\\b" to "com.mojang.blaze3d.platform.InputConstants.KEY_$1",
        "\\bGLFW\\.GLFW_MOUSE_BUTTON_(LEFT|RIGHT)\\b" to "com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_$1",
        // opening a folder moved too
        "\\bUtil\\.getPlatform\\(\\)\\.openPath\\(" to "com.mojang.blaze3d.Blaze3D.openPath(",
        // a world clock's time is read from its instance
        "\\.clockManager\\(\\)\\.getTotalTicks\\(([^()]*)\\)" to ".clockManager().getInstance($1).totalTicks()",
    )
}
