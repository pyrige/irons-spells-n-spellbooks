package io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob;

import io.redspace.ironsspellbooks.IronsSpellbooks;
import io.redspace.ironsspellbooks.capabilities.magic.PlayerMagicData;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import io.redspace.ironsspellbooks.spells.AbstractSpell;
import io.redspace.ironsspellbooks.spells.CastSource;
import io.redspace.ironsspellbooks.spells.CastType;
import io.redspace.ironsspellbooks.spells.SpellType;
import io.redspace.ironsspellbooks.spells.ender.TeleportSpell;
import io.redspace.ironsspellbooks.spells.fire.BurningDashSpell;
import io.redspace.ironsspellbooks.util.Utils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.UUID;

public abstract class AbstractSpellCastingMob extends Monster implements GeoEntity {
    public static final ResourceLocation modelResource = new ResourceLocation(IronsSpellbooks.MODID, "geo/abstract_casting_mob.geo.json");
    public static final ResourceLocation textureResource = new ResourceLocation(IronsSpellbooks.MODID, "textures/entity/abstract_casting_mob/abstract_casting_mob.png");
    public static final ResourceLocation animationInstantCast = new ResourceLocation(IronsSpellbooks.MODID, "animations/casting_animations.json");
    private static final EntityDataAccessor<SyncedSpellData> DATA_SPELL = SynchedEntityData.defineId(AbstractSpellCastingMob.class, SyncedSpellData.SYNCED_SPELL_DATA);
    private static final EntityDataAccessor<Boolean> DATA_CANCEL_CAST = SynchedEntityData.defineId(AbstractSpellCastingMob.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_DRINKING_POTION = SynchedEntityData.defineId(AbstractSpellCastingMob.class, EntityDataSerializers.BOOLEAN);
    private final PlayerMagicData playerMagicData = new PlayerMagicData(true);
    private static final AttributeModifier SPEED_MODIFIER_DRINKING = new AttributeModifier(UUID.fromString("5CD17E52-A79A-43D3-A529-90FDE04B181E"), "Drinking speed penalty", -0.5D, AttributeModifier.Operation.MULTIPLY_TOTAL);

    private @Nullable AbstractSpell castingSpell;
    private final EnumMap<SpellType, AbstractSpell> spells = new EnumMap<>(SpellType.class);
    private int drinkTime;
    public boolean hasUsedSingleAttack;

    protected AbstractSpellCastingMob(EntityType<? extends Monster> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
        playerMagicData.setSyncedData(new SyncedSpellData(this));
    }

    public PlayerMagicData getPlayerMagicData() {
        return playerMagicData;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SPELL, new SyncedSpellData(-1));
        this.entityData.define(DATA_CANCEL_CAST, false);
        this.entityData.define(DATA_DRINKING_POTION, false);
        //irons_spellbooks.LOGGER.debug("ASCM.defineSynchedData DATA_SPELL:{}", DATA_SPELL);
    }

    public boolean isDrinkingPotion() {
        return entityData.get(DATA_DRINKING_POTION);
    }

    protected void setDrinkingPotion(boolean drinkingPotion) {
        this.entityData.set(DATA_DRINKING_POTION, drinkingPotion);
    }

    public void startDrinkingPotion() {
        if (!level().isClientSide) {
            setDrinkingPotion(true);
            drinkTime = 35;


            AttributeInstance attributeinstance = this.getAttribute(Attributes.MOVEMENT_SPEED);
            attributeinstance.removeModifier(SPEED_MODIFIER_DRINKING);
            attributeinstance.addTransientModifier(SPEED_MODIFIER_DRINKING);
        }
    }

    private void finishDrinkingPotion() {
        setDrinkingPotion(false);
        this.heal(Math.min(10, getMaxHealth() / 4));
        this.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_MODIFIER_DRINKING);
        if (!this.isSilent()) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.WITCH_DRINK, this.getSoundSource(), 1.0F, 0.8F + this.random.nextFloat() * 0.4F);
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> pKey) {
        //irons_spellbooks.LOGGER.debug("ASCM.onSyncedDataUpdated ENTER level().isClientSide:{} {}", level().isClientSide, pKey);
        super.onSyncedDataUpdated(pKey);

        if (!level().isClientSide) {
            return;
        }

        if (pKey.getId() == DATA_CANCEL_CAST.getId()) {
            //IronsSpellbooks.LOGGER.debug("onSyncedDataUpdated DATA_CANCEL_CAST");
            cancelCast();
        }

        if (pKey.getId() == DATA_SPELL.getId()) {
            //IronsSpellbooks.LOGGER.debug("onSyncedDataUpdated DATA_SPELL");
            var isCasting = playerMagicData.isCasting();
            var syncedSpellData = entityData.get(DATA_SPELL);
            //irons_spellbooks.LOGGER.debug("ASCM.onSyncedDataUpdated(DATA_SPELL) {} {}", level().isClientSide, syncedSpellData);
            playerMagicData.setSyncedData(syncedSpellData);

            if (!syncedSpellData.isCasting() && isCasting) {
                castComplete();
            } else if (syncedSpellData.isCasting() && !isCasting)/* if (syncedSpellData.getCastingSpellType().getCastType() == CastType.CONTINUOUS)*/ {
                var spellType = SpellType.getTypeFromValue(syncedSpellData.getCastingSpellId());
                initiateCastSpell(spellType, syncedSpellData.getCastingSpellLevel());
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag pCompound) {
        super.addAdditionalSaveData(pCompound);
        playerMagicData.getSyncedData().saveNBTData(pCompound);
        pCompound.putBoolean("usedSpecial", hasUsedSingleAttack);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag pCompound) {
        super.readAdditionalSaveData(pCompound);
        var syncedSpellData = new SyncedSpellData(this);
        syncedSpellData.loadNBTData(pCompound);
        playerMagicData.setSyncedData(syncedSpellData);
        IronsSpellbooks.LOGGER.debug("ACM.readAdditionalSaveData.usedSpecial: {}", pCompound.getBoolean("usedSpecial"));
        hasUsedSingleAttack = pCompound.getBoolean("usedSpecial");
    }

    public void doSyncSpellData() {
        //Need a deep clone of the object because set does a basic object ref compare to trigger the update. Do not remove the deepClone
        entityData.set(DATA_SPELL, playerMagicData.getSyncedData().deepClone());
    }

    public void cancelCast() {
        if (isCasting()) {
            if (level().isClientSide) {
                cancelCastAnimation = true;
            } else {
                //Need to ensure we pass a different value if we want the data to sync
                entityData.set(DATA_CANCEL_CAST, !entityData.get(DATA_CANCEL_CAST));
            }

            castComplete();
        }

    }

    private void castComplete() {
        //irons_spellbooks.LOGGER.debug("ASCM.castComplete isClientSide:{}", level().isClientSide);
        if (!level().isClientSide) {
            castingSpell.onServerCastComplete(level(), this, playerMagicData, false);
        } else {
            playerMagicData.resetCastingState();
        }

        castingSpell = null;
    }

    public void startAutoSpinAttack(int pAttackTicks) {
        this.autoSpinAttackTicks = pAttackTicks;
        if (!this.level().isClientSide) {
            this.setLivingEntityFlag(4, true);
        }
        //Lil trick
        this.setYRot((float) (Math.atan2(getDeltaMovement().x, getDeltaMovement().z) * Mth.RAD_TO_DEG));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        //irons_spellbooks.LOGGER.debug("AbstractSpellCastingMob.aiStep");

        //Should basically be only used for client stuff

        if (!level().isClientSide || castingSpell == null) {
            return;
        }

        if (playerMagicData.getCastDurationRemaining() <= 0) {
            if (castingSpell.getCastType() == CastType.INSTANT) {
                castingSpell.onClientPreCast(level(), this, InteractionHand.MAIN_HAND, playerMagicData);
                castComplete();
            }
        } else {
            //Actively casting a long cast or continuous cast

        }

    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        //irons_spellbooks.LOGGER.debug("AbstractSpellCastingMob.customServerAiStep");
        if (isDrinkingPotion()) {
            if (drinkTime-- <= 0) {
                finishDrinkingPotion();
            } else if (drinkTime % 4 == 0)
                if (!this.isSilent())
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_DRINK, this.getSoundSource(), 1.0F, this.level().random.nextFloat() * 0.1F + 0.9F);

        }

        if (castingSpell == null || entityData.isDirty()) {
            return;
        }

        playerMagicData.handleCastDuration();

        if (playerMagicData.isCasting()) {
            castingSpell.onServerCastTick(level(), this, playerMagicData);
        }
        this.forceLookAtTarget(getTarget());
        if (playerMagicData.getCastDurationRemaining() <= 0) {
            if (castingSpell.getCastType() == CastType.LONG || castingSpell.getCastType() == CastType.CHARGE || castingSpell.getCastType() == CastType.INSTANT) {
                //irons_spellbooks.LOGGER.debug("ASCM.customServerAiStep: onCast.1 {}", castingSpell.getSpellType());
                castingSpell.onCast(level(), this, playerMagicData);
            }
            castComplete();
        } else if (castingSpell.getCastType() == CastType.CONTINUOUS) {
            if ((playerMagicData.getCastDurationRemaining() + 1) % 10 == 0) {
                //irons_spellbooks.LOGGER.debug("ASCM.customServerAiStep: onCast.2 {}", castingSpell.getSpellType());
                castingSpell.onCast(level(), this, playerMagicData);
            }
        }
    }

    public void initiateCastSpell(SpellType spellType, int spellLevel) {
        if (spellType == SpellType.NONE_SPELL) {
            castingSpell = null;
            return;
        }

        if (level().isClientSide) {
            cancelCastAnimation = false;
        }

        //irons_spellbooks.LOGGER.debug("ASCM.initiateCastSpell: {} {} isClientSide:{}", spellType, spellLevel, level().isClientSide);
        castingSpell = spells.computeIfAbsent(spellType, key -> AbstractSpell.getSpell(spellType, spellLevel));
        if (!castingSpell.checkPreCastConditions(level(), this, playerMagicData)) {
            castingSpell = null;
            return;
        }

        if (spellType == SpellType.TELEPORT_SPELL || spellType == SpellType.FROST_STEP_SPELL) {
            setTeleportLocationBehindTarget(10);
        } else if (spellType == SpellType.BLOOD_STEP_SPELL) {
            setTeleportLocationBehindTarget(3);
        } else if (spellType == SpellType.BURNING_DASH_SPELL) {
            setBurningDashDirectionData();
        }

        playerMagicData.initiateCast(castingSpell.getID(), castingSpell.getLevel(this), castingSpell.getEffectiveCastTime(this), CastSource.MOB);

        if (!level().isClientSide) {
            castingSpell.onServerPreCast(level(), this, playerMagicData);
        }
    }

    public boolean isCasting() {
        return entityData.get(DATA_SPELL).isCasting();
    }

    public void setTeleportLocationBehindTarget(int distance) {
        var target = getTarget();
        if (target != null) {
            var rotation = target.getLookAngle().normalize().scale(-distance);
            var pos = target.position();
            var teleportPos = rotation.add(pos);

            boolean valid = false;
            for (int i = 0; i < 24; i++) {
                teleportPos = target.position().subtract(new Vec3(0, 0, distance / (float) (i / 7 + 1)).yRot(-(target.getYRot() + i * 45) * Mth.DEG_TO_RAD));
                int y = Utils.findRelativeGroundLevel(target.level(), teleportPos, 5);
                teleportPos = new Vec3(teleportPos.x, y, teleportPos.z);
                var bb = this.getBoundingBox();
                var reposBB = bb.move(teleportPos.subtract(target.position()));
                if (!level().collidesWithSuffocatingBlock(this, reposBB)) {
                    valid = true;
                    break;
                }

            }
            if (valid)
                playerMagicData.setAdditionalCastData(new TeleportSpell.TeleportData(teleportPos));
        }
    }

    public void setBurningDashDirectionData() {
        playerMagicData.setAdditionalCastData(new BurningDashSpell.BurningDashDirectionOverrideCastData());
    }

    private void forceLookAtTarget(LivingEntity target) {
//        if (target != null) {
//            lookAt(target, 1, 1);
//            //setOldPosAndRot();
//        }
        if (target != null) {
            double d0 = target.getX() - this.getX();
            double d2 = target.getZ() - this.getZ();
            double d1 = target.getEyeY() - this.getEyeY();

            double d3 = Math.sqrt(d0 * d0 + d2 * d2);
            float f = (float) (Mth.atan2(d2, d0) * (double) (180F / (float) Math.PI)) - 90.0F;
            float f1 = (float) (-(Mth.atan2(d1, d3) * (double) (180F / (float) Math.PI)));
            this.setXRot(f1 % 360);
            this.setYRot(f % 360);
        }
    }

    private void addClientSideParticles() {
        double d0 = .4d;
        double d1 = .3d;
        double d2 = .35d;
        float f = this.yBodyRot * ((float) Math.PI / 180F) + Mth.cos((float) this.tickCount * 0.6662F) * 0.25F;
        float f1 = Mth.cos(f);
        float f2 = Mth.sin(f);
        this.level().addParticle(ParticleTypes.ENTITY_EFFECT, this.getX() + (double) f1 * 0.6D, this.getY() + 1.8D, this.getZ() + (double) f2 * 0.6D, d0, d1, d2);
        this.level().addParticle(ParticleTypes.ENTITY_EFFECT, this.getX() - (double) f1 * 0.6D, this.getY() + 1.8D, this.getZ() - (double) f2 * 0.6D, d0, d1, d2);
    }

    /**
     * GeckoLib Animations
     **/
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private SpellType lastCastSpellType = SpellType.NONE_SPELL;
    private boolean cancelCastAnimation = false;
    private final RawAnimation idle = RawAnimation.begin().thenLoop("blank");
    private final AnimationController animationControllerOtherCast = new AnimationController(this, "other_casting", 0, this::otherCastingPredicate);
    private final AnimationController animationControllerInstantCast = new AnimationController(this, "instant_casting", 0, this::instantCastingPredicate);
    private final AnimationController animationControllerLongCast = new AnimationController(this, "long_casting", 0, this::longCastingPredicate);

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }


    @Override
    public void triggerAnim(@org.jetbrains.annotations.Nullable String controllerName, String animName) {
        GeoEntity.super.triggerAnim(controllerName, animName);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllerRegistrar) {
        controllerRegistrar.add(animationControllerOtherCast);
        controllerRegistrar.add(animationControllerInstantCast);
        controllerRegistrar.add(animationControllerLongCast);
        controllerRegistrar.add(new AnimationController(this, "idle", 0, this::idlePredicate));
    }

    private PlayState idlePredicate(AnimationState event) {
        event.getController().setAnimation(idle);
        return PlayState.STOP;
    }

    private PlayState instantCastingPredicate(AnimationState event) {
        if (cancelCastAnimation) {
            return PlayState.STOP;
        }

        var controller = event.getController();
        if (isCasting() && castingSpell != null && castingSpell.getCastType() == CastType.INSTANT && controller.getAnimationState() == AnimationController.State.STOPPED) {
            setStartAnimationFromSpell(controller, castingSpell);
        }
        return PlayState.CONTINUE;
    }

    private PlayState longCastingPredicate(AnimationState event) {
        if (cancelCastAnimation) {
            return PlayState.STOP;
        }

        var controller = event.getController();
        if (isCasting() && castingSpell != null && castingSpell.getCastType() == CastType.LONG && controller.getAnimationState() == AnimationController.State.STOPPED) {
            setStartAnimationFromSpell(controller, castingSpell);
        }

        if (!isCasting() /*&& lastCastSpellType.getCastType() == CastType.LONG*/) {
            setFinishAnimationFromSpell(controller, lastCastSpellType);
        }

        return PlayState.CONTINUE;
    }

    private PlayState otherCastingPredicate(AnimationState event) {
        if (cancelCastAnimation) {
            return PlayState.STOP;
        }

        var controller = event.getController();
        if (isCasting() && castingSpell != null && controller.getAnimationState() == AnimationController.State.STOPPED) {
            if (castingSpell.getCastType() == CastType.CONTINUOUS || castingSpell.getCastType() == CastType.CHARGE) {
                setStartAnimationFromSpell(controller, castingSpell);
            }
            return PlayState.CONTINUE;
        }

        if (isCasting()) {
            return PlayState.CONTINUE;
        } else {
            return PlayState.STOP;
        }
    }

    private void setStartAnimationFromSpell(AnimationController controller, AbstractSpell spell) {
        spell.getCastStartAnimation().getForMob().ifPresent(animationBuilder -> {
            //controller.markNeedsReload();
            controller.setAnimation(animationBuilder);
            lastCastSpellType = spell.getSpellType();
            cancelCastAnimation = false;
        });
    }

    private void setFinishAnimationFromSpell(AnimationController controller, SpellType spellType) {
        var spell = AbstractSpell.getSpell(spellType, 1);
        spell.getCastFinishAnimation().getForMob().ifPresent(animationBuilder -> {
            //controller.markNeedsReload();
            controller.setAnimation(animationBuilder);
            lastCastSpellType = SpellType.NONE_SPELL;
            cancelCastAnimation = false;
        });
    }

    public boolean isAnimating() {
        return isCasting()
                || (animationControllerOtherCast.getAnimationState() != AnimationController.State.STOPPED)
                || (animationControllerInstantCast.getAnimationState() != AnimationController.State.STOPPED);
    }

    public boolean shouldBeExtraAnimated() {
        return true;
    }

    public boolean shouldAlwaysAnimateHead() {
        return true;
    }

    public boolean shouldAlwaysAnimateLegs() {
        return true;
    }

    public boolean shouldPointArmsWhileCasting() {
        return true;
    }
}
