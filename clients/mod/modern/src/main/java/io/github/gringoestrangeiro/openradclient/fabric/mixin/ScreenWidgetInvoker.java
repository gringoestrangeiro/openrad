package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Modern Fabric screen API 5.x no longer exposes Screens.getButtons. */
@Mixin(Screen.class)
public interface ScreenWidgetInvoker {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T openrad$addRenderableWidget(T widget);
}
