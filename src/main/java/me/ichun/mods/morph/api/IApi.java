package me.ichun.mods.morph.api;

import me.ichun.mods.morph.api.morph.MorphInfo;
import me.ichun.mods.morph.api.morph.MorphVariant;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;

public interface IApi
{
    @Nullable
    default MorphInfo getMorphInfo(PlayerEntity player)
    {
        return null;
    }

    default boolean canAcquireBiomass(PlayerEntity player, LivingEntity living)
    {
        return false;
    }

    default boolean canAcquireMorph(PlayerEntity player, LivingEntity living)
    {
        return false;
    }

    @Nullable
    default MorphVariant createVariant(LivingEntity living)
    {
        return null;
    }

    default void acquireMorph(ServerPlayerEntity player, MorphVariant variant){}

    default boolean morphTo(ServerPlayerEntity player, MorphVariant variant) { return false; }

    default Map<ResourceLocation, Boolean> getSupportedAttributes() { return Collections.emptyMap(); }

    @Nullable
    default LivingEntity getActiveMorphEntity(PlayerEntity player) { return null; }

    @Nullable
    default ResourceLocation getMorphSkinTexture() { return null; }
}
