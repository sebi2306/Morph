package me.ichun.mods.morph.api.morph;

import me.ichun.mods.ichunutil.common.entity.util.EntityHelper;
import me.ichun.mods.morph.api.mixin.LivingEntityInvokerMixin;
import me.ichun.mods.morph.api.mob.trait.Trait;
import me.ichun.mods.morph.client.entity.EntityBiomassAbility;
import me.ichun.mods.morph.client.render.MorphRenderHandler;
import me.ichun.mods.morph.common.Morph;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.Attribute;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MorphInfo
{
    @CapabilityInject(MorphInfo.class)
    public static Capability<MorphInfo> CAPABILITY_INSTANCE;
    public static final ResourceLocation CAPABILITY_IDENTIFIER = new ResourceLocation("morph", "capability_morph_state");
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(-70000000);// -70 million. We reduce even further as we use this more, negative ent IDs prevent collision with real entities (with positive IDs starting with 0)

    public final PlayerEntity player; //TODO do I need to set a new one when change dimension?

    private final Random rand = new Random();

    @Nullable
    public MorphState prevState;
    @Nullable
    public MorphState nextState;

    public int morphTime; //this is the time the player has been morphing for.

    public int morphingTime; //this is the time it takes for a player to Morph

    public boolean firstTick = true;
    public int playSoundTime = -1;

    public boolean requested; //Never checked on server.

    @OnlyIn(Dist.CLIENT)
    public MorphRenderHandler.MorphTransitionState transitionState;
    @OnlyIn(Dist.CLIENT)
    public EntityBiomassAbility entityBiomassAbility;

    public MorphInfo(PlayerEntity player)
    {
        this.player = player;
    }

    public void tick() //returns true if the player is considered "morphed"
    {
        if(!isMorphed())
        {
            return;
        }

        float transitionProgress = getTransitionProgressLinear(1F);

        if(firstTick)
        {
            firstTick = false;
            player.recalculateSize();
            applyAttributeModifiers(transitionProgress);
        }

        //TODO check player resize on sleeping


        if(transitionProgress < 1.0F) // is morphing
        {
            if(!player.world.isRemote)
            {
                if(playSoundTime < 0)
                {
                    playSoundTime = Math.max(0, (int)((morphingTime - 60) / 2F)); // our sounds are 3 seconds long. play it in the middle of the morph
                }

                if(morphTime == playSoundTime)
                {
                    player.world.playMovingSound(null, player, Morph.Sounds.MORPH.get(), player.getSoundCategory(), 1.0F, 1.0F);
                }
            }
            prevState.tick(player, transitionProgress > 0F);
            nextState.tick(player, true);

            float prevStateTraitStrength = 1F - MathHelper.clamp(transitionProgress / 0.5F, 0F, 1F);
            float nextStateTraitStrength = MathHelper.clamp((transitionProgress - 0.5F) / 0.5F, 0F, 1F);

            ArrayList<Trait<?>> prevTraits = new ArrayList<>(prevState.traits);
            for(Trait trait : nextState.traits)
            {
                boolean foundTranslatableTrait = false;
                for(int i = prevTraits.size() - 1; i >= 0; i--)
                {
                    Trait<?> prevTrait = prevTraits.get(i);
                    if(prevTrait.canTransitionTo(trait))
                    {
                        prevTraits.remove(i); //remove it

                        foundTranslatableTrait = true;

                        trait.doTransitionalTick(prevTrait, transitionProgress);

                        break;
                    }
                }

                if(!foundTranslatableTrait) //only nextState has this trait
                {
                    trait.doTick(nextStateTraitStrength);
                }
            }

            for(Trait<?> value : prevTraits)
            {
                value.doTick(prevStateTraitStrength);
            }
        }
        else
        {
            nextState.tick(player, false);
            nextState.tickTraits();
        }

        morphTime++;
        if(morphTime <= morphingTime) //still morphing
        {
            player.recalculateSize();
            applyAttributeModifiers(transitionProgress);
        }
        else if(Morph.configServer.aggressiveSizeRecalculation)
        {
            player.recalculateSize();
        }

        if(morphTime == morphingTime)
        {
            removeAttributeModifiersFromPrevState();
            setPrevState(null); //bye bye last state. We don't need you anymore.

            if(player.world.isRemote)
            {
                if(transitionState != null)
                {
                    transitionState = null;
                }
            }

            if(nextState.variant.id.equals(EntityType.PLAYER.getRegistryName()) && nextState.variant.thisVariant.identifier.equals(MorphVariant.IDENTIFIER_DEFAULT_PLAYER_STATE))
            {
                setNextState(null);
            }
        }

        if(player.world.isRemote)
        {
            if(entityBiomassAbility != null && entityBiomassAbility.removed)
            {
                entityBiomassAbility = null; //have it, GC
            }
        }
    }

    public boolean isMorphed()
    {
        return nextState != null;
    }

    public float getMorphProgress(float partialTick)
    {
        if(prevState == null || nextState == null || morphingTime <= 0)
        {
            return 1.0F;
        }

        return MathHelper.clamp((morphTime + partialTick) / morphingTime, 0F, 1F);
    }

    public float getTransitionProgressLinear(float partialTick) //10 - 60 - 10 : fade to black - transition - fade to ent
    {
        float morphProgress = getMorphProgress(partialTick);
        return MathHelper.clamp((morphProgress - 0.125F) / 0.75F, 0F, 1F);
    }

    public float getTransitionProgressSine(float partialTick)
    {
        return sineifyProgress(getTransitionProgressLinear(partialTick));
    }

    public EntitySize getMorphSize(float partialTick)
    {
        float morphProgress = getMorphProgress(partialTick);
        if(morphProgress < 1F)
        {
            float transitionProgress = getTransitionProgressSine(partialTick);
            if(transitionProgress <= 0F)
            {
                LivingEntity prevInstance = prevState.getEntityInstance(player.world, player.getGameProfile().getId());
                prevInstance.recalculateSize();
                return prevInstance.size;
            }
            else if(transitionProgress >= 1F)
            {
                LivingEntity nextInstance = nextState.getEntityInstance(player.world, player.getGameProfile().getId());
                nextInstance.recalculateSize();
                return nextInstance.size;
            }
            else
            {
                LivingEntity prevInstance = prevState.getEntityInstance(player.world, player.getGameProfile().getId());
                prevInstance.recalculateSize();
                LivingEntity nextInstance = nextState.getEntityInstance(player.world, player.getGameProfile().getId());
                nextInstance.recalculateSize();
                EntitySize prevSize = prevInstance.size;
                EntitySize nextSize = nextInstance.size;
                return EntitySize.flexible(prevSize.width + (nextSize.width - prevSize.width) * transitionProgress, prevSize.height + (nextSize.height - prevSize.height) * transitionProgress);
            }
        }
        else
        {
            LivingEntity nextInstance = nextState.getEntityInstance(player.world, player.getGameProfile().getId());
            nextInstance.recalculateSize();
            return nextInstance.size;
        }
    }

    public float getMorphEyeHeight(float partialTick)
    {
        float morphProgress = getMorphProgress(partialTick);
        if(morphProgress < 1F)
        {
            float transitionProgress = getTransitionProgressSine(partialTick);
            if(transitionProgress <= 0F)
            {
                return prevState.getEntityInstance(player.world, player.getGameProfile().getId()).getEyeHeight();
            }
            else if(transitionProgress >= 1F)
            {
                return nextState.getEntityInstance(player.world, player.getGameProfile().getId()).getEyeHeight();
            }
            else
            {
                float prevHeight = prevState.getEntityInstance(player.world, player.getGameProfile().getId()).getEyeHeight();
                float nextHeight = nextState.getEntityInstance(player.world, player.getGameProfile().getId()).getEyeHeight();
                return prevHeight + (nextHeight - prevHeight) * transitionProgress;
            }
        }
        else
        {
            return nextState.getEntityInstance(player.world, player.getGameProfile().getId()).getEyeHeight();
        }
    }

    private void setPrevState(@Nullable MorphState state)
    {
        if(prevState != null)
        {
            prevState.deactivateHooks();
        }

        prevState = state;

        if(prevState != null)
        {
            prevState.activateHooks();
        }
    }

    private void setNextState(@Nullable MorphState state)
    {
        if(nextState != null && nextState != prevState) //check to make sure prevState was not set to our current nextState
        {
            nextState.deactivateHooks();
        }

        nextState = state;

        if(nextState != null)
        {
            nextState.activateHooks();
        }
    }

    public void setNextState(MorphState state, int morphingTime) //sets the morph. If null, sets to no morph.
    {
        if(state != null)
        {
            if(nextState != null) //morphing from one morph to another
            {
                setPrevState(nextState);
            }
            else //just started morphing
            {
                MorphVariant variant = MorphVariant.createPlayerMorph(player.getGameProfile().getId(), true);
                variant.thisVariant.identifier = MorphVariant.IDENTIFIER_DEFAULT_PLAYER_STATE;
                setPrevState(new MorphState(variant, player));
            }

            this.morphTime = 0;
            this.morphingTime = morphingTime;
        }
        else
        {
            setPrevState(null);
            this.morphTime = 0;
            this.morphingTime = 0;
        }
        setNextState(state);
        playSoundTime = -1; //default
        player.recalculateSize();
    }

    public boolean isCurrentlyThisVariant(@Nonnull MorphVariant.Variant variant)
    {
        return (nextState != null && nextState.variant.thisVariant.identifier.equals(variant.identifier) || !isMorphed() && variant.identifier.equals(MorphVariant.IDENTIFIER_DEFAULT_PLAYER_STATE));
    }

    public void applyAttributeModifiers(float transitionProgress)
    {
        if(player.world.isRemote) //we don't touch the attributes on the client
        {
            return;
        }

        HashMap<Attribute, Double> attributeModifierAmount = new HashMap<>();

        //Add the next state's attribute modifier amounts
        for(Map.Entry<String, INBT> e : nextState.variant.nbtMorph.tagMap.entrySet())
        {
            String key = e.getKey();
            if(key.startsWith("attr_")) //it's an attribute key
            {
                ResourceLocation id = new ResourceLocation(key.substring(5));
                Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(id);
                if(attribute != null)
                {
                    final ModifiableAttributeInstance playerAttribute = player.getAttribute(attribute);
                    if(playerAttribute != null)
                    {
                        double baseValue = player.getBaseAttributeValue(attribute);
                        double modifierValue = nextState.variant.nbtMorph.getDouble(key) - baseValue;
                        attributeModifierAmount.put(attribute, modifierValue);
                    }
                }
            }
        }

        if(transitionProgress < 1.0F) //we still have a prev state, aka still morphing
        {
            HashSet<Attribute> prevStateAttrs = new HashSet<>();
            for(Map.Entry<String, INBT> e : prevState.variant.nbtMorph.tagMap.entrySet())
            {
                String key = e.getKey();
                if(key.startsWith("attr_")) //it's an attribute key
                {
                    ResourceLocation id = new ResourceLocation(key.substring(5));
                    Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(id);
                    if(attribute != null)
                    {
                        final ModifiableAttributeInstance playerAttribute = player.getAttribute(attribute);
                        if(playerAttribute != null)
                        {
                            double baseValue = player.getBaseAttributeValue(attribute);
                            double modifierValue = prevState.variant.nbtMorph.getDouble(key) - baseValue;

                            if(attributeModifierAmount.containsKey(attribute)) //the nextState also has this attribute
                            {
                                double val = modifierValue + (attributeModifierAmount.get(attribute) - modifierValue) * transitionProgress;
                                attributeModifierAmount.put(attribute, val);
                            }
                            else
                            {
                                attributeModifierAmount.put(attribute, modifierValue * (1F - transitionProgress)); //the strength of the attribute approaches 0
                            }
                            prevStateAttrs.add(attribute);
                        }
                    }
                }
            }

            for(Map.Entry<Attribute, Double> e : attributeModifierAmount.entrySet())
            {
                if(!prevStateAttrs.contains(e.getKey())) //this is added by nextState, we need to decrease the modifier since we're still transitioning
                {
                    e.setValue(e.getValue() * transitionProgress);
                }
            }
        }

        //add these modifiers to the player
        for(Map.Entry<Attribute, Double> e : attributeModifierAmount.entrySet())
        {
            final ModifiableAttributeInstance playerAttribute = player.getAttribute(e.getKey());
            if(playerAttribute != null)
            {
                rand.setSeed(Math.abs("MorphAttr".hashCode() * 1231543 + e.getKey().getRegistryName().toString().hashCode() * 268));
                UUID uuid = MathHelper.getRandomUUID(rand);

                double healthChange = 0D;

                if(playerAttribute.getAttribute().equals(Attributes.MAX_HEALTH)) //special casing for the max health
                {
                    //TODO HEALTH
                }

                //you can't reapply the same modifier, so lets remove it
                playerAttribute.removePersistentModifier(uuid);

                if(e.getValue() != 0) //if the modifier is non-zero, add it
                {
                    playerAttribute.applyPersistentModifier(new AttributeModifier(uuid, "MorphAttributeModifier:" + e.getKey().getRegistryName().toString(), e.getValue(), AttributeModifier.Operation.ADDITION));
                }
            }
        }
    }

    public void removeAttributeModifiersFromPrevState()
    {
        if(prevState != null) //just in case?
        {
            HashSet<Attribute> attributesToRemove = new HashSet<>();

            //Add the prev state's attributes
            for(Map.Entry<String, INBT> e : prevState.variant.nbtMorph.tagMap.entrySet())
            {
                String key = e.getKey();
                if(key.startsWith("attr_")) //it's an attribute key
                {
                    ResourceLocation id = new ResourceLocation(key.substring(5));
                    Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(id);
                    if(attribute != null)
                    {
                        attributesToRemove.add(attribute);
                    }
                }
            }

            //Add the prev state's attributes
            for(Map.Entry<String, INBT> e : nextState.variant.nbtMorph.tagMap.entrySet())
            {
                String key = e.getKey();
                if(key.startsWith("attr_")) //it's an attribute key
                {
                    ResourceLocation id = new ResourceLocation(key.substring(5));
                    Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(id);
                    if(attribute != null)
                    {
                        attributesToRemove.remove(attribute);
                    }
                }
            }

            for(Attribute attribute : attributesToRemove)
            {
                final ModifiableAttributeInstance playerAttribute = player.getAttribute(attribute);
                if(playerAttribute != null)
                {
                    rand.setSeed(Math.abs("MorphAttr".hashCode() * 1231543 + attribute.getRegistryName().toString().hashCode() * 268));
                    UUID uuid = MathHelper.getRandomUUID(rand);
                    playerAttribute.removePersistentModifier(uuid);
                }
            }
        }
    }

    public CompoundNBT write(CompoundNBT tag)
    {
        if(prevState != null)
        {
            tag.put("prevState", prevState.write(new CompoundNBT()));
        }

        if(nextState != null)
        {
            tag.put("nextState", nextState.write(new CompoundNBT()));
        }

        tag.putInt("morphTime", morphTime);
        tag.putInt("morphingTime", morphingTime);
        return tag;
    }

    public void read(CompoundNBT tag)
    {
        playSoundTime = -1; //default

        if(tag.contains("prevState"))
        {
            MorphState state = MorphState.createFromNbt(tag.getCompound("prevState"));
            if(state.variant.thisVariant != null)
            {
                if(nextState != null && nextState.equals(state) && tag.contains("nextState"))
                {
                    setPrevState(nextState);
                }
                else
                {
                    setPrevState(state);
                }
            }
        }
        else
        {
            setPrevState(null);
        }

        if(tag.contains("nextState"))
        {
            setNextState(MorphState.createFromNbt(tag.getCompound("nextState")));
            if(nextState.variant.thisVariant == null) //MorphState variants should ALWAYS have a thisVariant.
            {
                setPrevState(null);
                setNextState(null);
            }
        }
        else
        {
            setNextState(null);
        }

        morphTime = tag.getInt("morphTime");
        morphingTime = tag.getInt("morphingTime");

        player.recalculateSize();
    }

    public LivingEntity getActiveMorphEntity()
    {
        if(getMorphProgress(1F) < 0.5F)
        {
            return prevState.getEntityInstance(player.world, player.getGameProfile().getId());
        }
        else if(nextState != null)
        {
            return nextState.getEntityInstance(player.world, player.getGameProfile().getId());
        }
        return null;
    }

    private LivingEntity getActiveMorphEntityOrPlayer()
    {
        LivingEntity activeMorph = getActiveMorphEntity();
        if(activeMorph == null)
        {
            activeMorph = player;
        }

        return activeMorph;
    }

    public LivingEntity getActiveAppearanceEntity(float partialTick)
    {
        if(getMorphProgress(partialTick) < 1F) //morphing
        {
            float transitionProg = getTransitionProgressLinear(partialTick);
            if(transitionProg <= 0F)
            {
                return prevState.getEntityInstance(player.world, player.getGameProfile().getId());
            }
            else if(transitionProg >= 1F)
            {
                return nextState.getEntityInstance(player.world, player.getGameProfile().getId());
            }
            return null; //mid transition, no active appearance.
        }
        else if(nextState != null) //is morphed
        {
            return nextState.getEntityInstance(player.world, player.getGameProfile().getId());
        }
        else
        {
            return player;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public float getMorphSkinAlpha(float partialTick)
    {
        return Math.max(getMorphingSkinAlpha(partialTick), getAbilitySkinAlpha(partialTick));
    }

    @OnlyIn(Dist.CLIENT)
    private float getMorphingSkinAlpha(float partialTick) //similar code in MorphRenderHelper.renderMorphInfo
    {
        float morphProgress = getMorphProgress(partialTick);
        if(morphProgress < 1F)
        {
            float transitionProgress = getTransitionProgressSine(partialTick);
            if(transitionProgress <= 0F)
            {
                return sineifyProgress(morphProgress / 0.125F);
            }
            else if(transitionProgress >= 1F)
            {
                return 1F - sineifyProgress((morphProgress - 0.875F) / 0.125F);
            }
            return 1F;
        }

        return 0F;
    }

    @OnlyIn(Dist.CLIENT)
    private float getAbilitySkinAlpha(float partialTick)
    {
        if(entityBiomassAbility != null)
        {
            float alpha;
            if(entityBiomassAbility.age < entityBiomassAbility.fadeTime)
            {
                alpha = EntityHelper.sineifyProgress(MathHelper.clamp((entityBiomassAbility.age + partialTick) / entityBiomassAbility.fadeTime, 0F, 1F));
            }
            else if(entityBiomassAbility.age >= entityBiomassAbility.fadeTime + entityBiomassAbility.solidTime)
            {
                alpha = EntityHelper.sineifyProgress(1F - MathHelper.clamp((entityBiomassAbility.age - (entityBiomassAbility.fadeTime + entityBiomassAbility.solidTime) + partialTick) / entityBiomassAbility.fadeTime, 0F, 1F));
            }
            else
            {
                alpha = 1F;
            }
            return alpha;
        }
        return 0F;
    }

    @Nullable
    public SoundEvent getHurtSound(DamageSource source) {
        return ((LivingEntityInvokerMixin)getActiveMorphEntityOrPlayer()).callGetHurtSound(source);
    }

    @Nullable
    public SoundEvent getDeathSound() {
        return ((LivingEntityInvokerMixin)getActiveMorphEntityOrPlayer()).callGetDeathSound();
    }

    public SoundEvent getFallSound(int height) {
        return ((LivingEntityInvokerMixin)getActiveMorphEntityOrPlayer()).callGetFallSound(height);
    }

    public SoundEvent getDrinkSound(ItemStack stack) {
        return ((LivingEntityInvokerMixin)getActiveMorphEntityOrPlayer()).callGetDrinkSound(stack);
    }

    public SoundEvent getEatSound(ItemStack stack) {
        return getActiveMorphEntityOrPlayer().getEatSound(stack);
    }

    public float getSoundVolume()
    {
        if(nextState != null)
        {
            if(prevState != null)
            {
                float transitionProg = getTransitionProgressLinear(1F);

                float prevVolume = ((LivingEntityInvokerMixin)prevState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundVolume();
                float nextVolume = ((LivingEntityInvokerMixin)nextState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundVolume();

                return prevVolume + (nextVolume - prevVolume) * transitionProg;
            }
            else
            {
                return ((LivingEntityInvokerMixin)nextState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundVolume();
            }
        }

        return 1F;
    }

    public float getSoundPitch()
    {
        if(nextState != null)
        {
            if(prevState != null)
            {
                float transitionProg = getTransitionProgressLinear(1F);

                float prevPitch = ((LivingEntityInvokerMixin)prevState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundPitch();
                float nextPitch = ((LivingEntityInvokerMixin)nextState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundPitch();

                return prevPitch + (nextPitch - prevPitch) * transitionProg;
            }
            else
            {
                return ((LivingEntityInvokerMixin)nextState.getEntityInstance(player.world, player.getGameProfile().getId())).callGetSoundPitch();
            }
        }

        return 1F;
    }

    public static class CapProvider implements ICapabilitySerializable<CompoundNBT>
    {
        private final MorphInfo state;
        private final LazyOptional<MorphInfo> optional;

        public CapProvider(MorphInfo state)
        {
            this.state = state;
            this.optional = LazyOptional.of(() -> state);
        }

        @Nonnull
        @Override
        public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side)
        {
            if(cap == CAPABILITY_INSTANCE)
            {
                return optional.cast();
            }
            return LazyOptional.empty();
        }

        @Override
        public CompoundNBT serializeNBT()
        {
            return state.write(new CompoundNBT());
        }

        @Override
        public void deserializeNBT(CompoundNBT nbt)
        {
            state.read(nbt);
        }
    }

    //TAKEN FROM ClientEntityTracker
    public static int getNextEntId()
    {
        return NEXT_ENTITY_ID.getAndDecrement();
    }

    public static float sineifyProgress(float progress) //0F - 1F; Yay math
    {
        return ((float)Math.sin(Math.toRadians(-90F + (180F * progress))) / 2F) + 0.5F;
    }
}
