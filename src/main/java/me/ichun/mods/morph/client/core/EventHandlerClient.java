package me.ichun.mods.morph.client.core;

import me.ichun.mods.morph.api.morph.MorphInfo;
import me.ichun.mods.morph.client.render.MorphRenderHandler;
import me.ichun.mods.morph.common.Morph;
import me.ichun.mods.morph.common.morph.MorphHandler;
import me.ichun.mods.morph.common.morph.save.PlayerMorphData;
import me.ichun.mods.morph.common.packet.PacketRequestMorphInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class EventHandlerClient
{
    public PlayerMorphData morphData;

    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event)
    {
        if(MorphRenderHandler.isRenderingMorph) //we're rendering a player morph, forgetaboutit
        {
            return;
        }

        MorphRenderHandler.restoreShadowSize(event.getRenderer());

        PlayerEntity player = event.getPlayer();
        if(!player.removed)
        {
            MorphInfo info = MorphHandler.INSTANCE.getMorphInfo(player);
            if(!info.requested)
            {
                Morph.channel.sendToServer(new PacketRequestMorphInfo(player.getGameProfile().getId()));
                info.requested = true;
            }
            else if(info.isMorphed())
            {
                event.setCanceled(true);

                MorphRenderHandler.renderMorphInfo(player, info, event.getMatrixStack(), event.getBuffers(), event.getLight(), event.getPartialRenderTick());

                MorphRenderHandler.setShadowSize(event.getRenderer(), player, info, event.getPartialRenderTick());
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event)
    {
        if(event.phase == TickEvent.Phase.START && Minecraft.getInstance().player != null && !Minecraft.getInstance().player.removed)
        {
            MorphInfo info = MorphHandler.INSTANCE.getMorphInfo(Minecraft.getInstance().player);
            if(info.isMorphed() && (info.getMorphProgress(event.renderTickTime) < 1F || Morph.configServer.aggressiveSizeRecalculation)) //is morphing
            {
                Minecraft.getInstance().player.eyeHeight = info.getMorphEyeHeight(event.renderTickTime);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if(!event.player.removed && event.player == Minecraft.getInstance().player && event.player.ticksExisted == 10)
        {
            MorphInfo info = MorphHandler.INSTANCE.getMorphInfo(event.player);
            if(!info.requested)
            {
                Morph.channel.sendToServer(new PacketRequestMorphInfo(event.player.getGameProfile().getId()));
                info.requested = true;
            }
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(ClientPlayerNetworkEvent.LoggedOutEvent event)
    {
        morphData = null;
    }
}
