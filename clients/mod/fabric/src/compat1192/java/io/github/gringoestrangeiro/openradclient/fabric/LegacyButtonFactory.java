package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Version-specific replacement for Button.builder(), introduced after Minecraft 1.19.2. */
public final class LegacyButtonFactory {
    private LegacyButtonFactory() { }

    public static Builder builder(Component label, Button.OnPress onPress) {
        return new Builder(label, onPress);
    }

    public static final class Builder {
        private final Component label;
        private final Button.OnPress onPress;
        private int x;
        private int y;
        private int width = 200;
        private int height = 20;

        private Builder(Component label, Button.OnPress onPress) {
            this.label = label;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Button build() {
            return new Button(x, y, width, height, label, onPress);
        }
    }
}
