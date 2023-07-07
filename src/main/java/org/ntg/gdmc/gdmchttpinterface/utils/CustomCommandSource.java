package org.ntg.gdmc.gdmchttpinterface.utils;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

public class CustomCommandSource implements CommandSource {

    private Component lastOutput;

    @Override
    public void sendSystemMessage(Component output) {
        this.lastOutput = output;
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return true;
    }

    public Component getLastOutput() {
        return this.lastOutput;
    }
}
