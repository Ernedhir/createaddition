package com.mrh0.createaddition.blocks.liquid_blaze_burner;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.mrh0.createaddition.index.CARecipes;
import com.mrh0.createaddition.network.IObserveTileEntity;
import com.mrh0.createaddition.network.ObservePacket;
import com.mrh0.createaddition.recipe.FluidRecipeWrapper;
import com.mrh0.createaddition.recipe.liquid_burning.LiquidBurningRecipe;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllTags.AllItemTags;
import com.simibubi.create.content.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.foundation.utility.animation.LerpedFloat.Chaser;
import io.github.fabricators_of_create.porting_lib.fluids.FluidStack;
import io.github.fabricators_of_create.porting_lib.transfer.TransferUtil;
import io.github.fabricators_of_create.porting_lib.transfer.callbacks.TransactionCallback;
import io.github.fabricators_of_create.porting_lib.transfer.fluid.FluidTank;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity.FuelType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.FullItemFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

public class LiquidBlazeBurnerBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation, IObserveTileEntity, SidedStorageBlockEntity {
	public static final int MAX_HEAT_CAPACITY = 10000;

	public static final long FLUID_CONSUMPTION_THRESHOLD = FluidConstants.BUCKET / 10;
	public static final long FLUID_CAPACITY = FluidConstants.BUCKET * 4;

	protected FuelType activeFuel;
	protected int remainingBurnTime;
	protected LerpedFloat headAnimation;
	protected LerpedFloat headAngle;
	protected boolean isCreative;
	protected boolean goggles;
	protected boolean hat;

	public LiquidBlazeBurnerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		activeFuel = FuelType.NONE;
		remainingBurnTime = 0;
		headAnimation = LerpedFloat.linear();
		headAngle = LerpedFloat.angular();
		isCreative = false;
		goggles = false;

		headAngle.startWithValue((AngleHelper.horizontalAngle(state.getOptionalValue(LiquidBlazeBurnerBlock.FACING)
			.orElse(Direction.SOUTH)) + 180) % 360);

		tankInventory = createInventory();
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> list) {

	}

	// Custom fluid handling
	protected FluidTank tankInventory;

	private Optional<LiquidBurningRecipe> recipeCache = Optional.empty();
	private Fluid lastFluid = null;
	private int updateTimeout = 10;
	private boolean changed = true;

	protected SmartFluidTank createInventory() {
		return new SmartFluidTank(FLUID_CAPACITY, this::onFluidStackChanged);
	}

	protected void onFluidStackChanged(FluidStack newFluidStack) {
		if (!hasLevel())
			return;
		update(newFluidStack);
	}

	private void update(FluidStack stack) {
		if(level.isClientSide())
			return;
		if(stack.getFluid() != lastFluid)
			recipeCache = find(stack, level);
		lastFluid = stack.getFluid();
		changed = true;
	}

	public Optional<LiquidBurningRecipe> find(FluidStack stack, Level level) {
		if(stack == null)
			return Optional.empty();
		if(level == null)
			return Optional.empty();
		if(CARecipes.LIQUID_BURNING_TYPE.get() == null)
			return Optional.empty();
		return level.getRecipeManager().getRecipeFor(CARecipes.LIQUID_BURNING_TYPE.get(), new FluidRecipeWrapper(stack), level);
	}

	@Override
	public Storage<FluidVariant> getFluidStorage(@Nullable Direction side) {
		return tankInventory;
	}

	public boolean first = true;
	public void burningTick() {
		if(level.isClientSide())
			return;

		if(first)
			update(tankInventory.getFluid());
		first = false;

		if (recipeCache.isEmpty())
			return;

		if (tankInventory.getFluidAmount() < FLUID_CONSUMPTION_THRESHOLD)
			return;

		FuelType newActiveFuel = recipeCache.get().isSuperheated() ? FuelType.SPECIAL : FuelType.NORMAL;

		if(remainingBurnTime >= 1 && !(activeFuel == FuelType.NORMAL && newActiveFuel == FuelType.SPECIAL))
			return;

		activeFuel = newActiveFuel;

		remainingBurnTime += recipeCache.get().getBurnTime() / 10;


		TransferUtil.extractAnyFluid(tankInventory, FLUID_CONSUMPTION_THRESHOLD);


		BlazeBurnerBlock.HeatLevel prev = getHeatLevelFromBlock();
		playSound();
		updateBlockState();

		if (prev != getHeatLevelFromBlock()) {
			level.playSound(null, worldPosition, SoundEvents.BLAZE_AMBIENT, SoundSource.BLOCKS,
				.125f + level.random.nextFloat() * .125f, 1.15f - level.random.nextFloat() * .25f);

			spawnParticleBurst(activeFuel == FuelType.SPECIAL);
		}
	}


	public FuelType getActiveFuel() {
		return activeFuel;
	}

	public int getRemainingBurnTime() {
		return remainingBurnTime;
	}

	public boolean isCreative() {
		return isCreative;
	}

	@Override
	public void tick() {
		super.tick();

		if (level.isClientSide) {
			tickAnimation();
			if (!isVirtual())
				spawnParticles(getHeatLevelFromBlock(), 1);
			return;
		}
		
		if (remainingBurnTime > 0 && !isCreative)
			remainingBurnTime--;

		burningTick();

		if (isCreative)
			return;

		if (activeFuel == FuelType.NORMAL)
			updateBlockState();
		if (remainingBurnTime > 0)
			return;

		if (activeFuel == FuelType.SPECIAL) {
			activeFuel = FuelType.NORMAL;
			remainingBurnTime = MAX_HEAT_CAPACITY / 2;
		} else
			activeFuel = FuelType.NONE;

		updateBlockState();
	}

	@Environment(EnvType.CLIENT)
	private void tickAnimation() {
		boolean active = getHeatLevelFromBlock().isAtLeast(BlazeBurnerBlock.HeatLevel.FADING) && isValidBlockAbove();

		if (!active) {
			float target = 0;
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null && !player.isInvisible()) {
				double x;
				double z;
				if (isVirtual()) {
					x = -4;
					z = -10;
				} else {
					x = player.getX();
					z = player.getZ();
				}
				double dx = x - (getBlockPos().getX() + 0.5);
				double dz = z - (getBlockPos().getZ() + 0.5);
				target = AngleHelper.deg(-Mth.atan2(dz, dx)) - 90;
			}
			target = headAngle.getValue() + AngleHelper.getShortestAngleDiff(headAngle.getValue(), target);
			headAngle.chase(target, .25f, Chaser.exp(5));
			headAngle.tickChaser();
		} else {
			headAngle.chase((AngleHelper.horizontalAngle(getBlockState().getOptionalValue(LiquidBlazeBurnerBlock.FACING)
				.orElse(Direction.SOUTH)) + 180) % 360, .125f, Chaser.EXP);
			headAngle.tickChaser();
		}

		headAnimation.chase(active ? 1 : 0, .25f, Chaser.exp(.25f));
		headAnimation.tickChaser();
	}

	@Override
	public void write(CompoundTag compound, boolean clientPacket) {
		if (!isCreative) {
			compound.putInt("fuelLevel", activeFuel.ordinal());
			compound.putInt("burnTimeRemaining", remainingBurnTime);
		} else
			compound.putBoolean("isCreative", true);
		if (goggles)
			compound.putBoolean("Goggles", true);
		if (hat)
			compound.putBoolean("TrainHat", true);
		compound.put("TankContent", tankInventory.writeToNBT(new CompoundTag()));
		super.write(compound, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, boolean clientPacket) {
		activeFuel = FuelType.values()[compound.getInt("fuelLevel")];
		remainingBurnTime = compound.getInt("burnTimeRemaining");
		isCreative = compound.getBoolean("isCreative");
		goggles = compound.contains("Goggles");
		hat = compound.contains("TrainHat");
		tankInventory.readFromNBT(compound.getCompound("TankContent"));
		super.read(compound, clientPacket);
	}

	public BlazeBurnerBlock.HeatLevel getHeatLevelFromBlock() {
		return BlazeBurnerBlock.getHeatLevelOf(getBlockState());
	}

	public void updateBlockState() {
		setBlockHeat(getHeatLevelFromFuelType(activeFuel));
	}

	protected void setBlockHeat(BlazeBurnerBlock.HeatLevel heat) {
		BlazeBurnerBlock.HeatLevel inBlockState = getHeatLevelFromBlock();
		if (inBlockState == heat)
			return;
		level.setBlockAndUpdate(worldPosition, getBlockState().setValue(LiquidBlazeBurnerBlock.HEAT_LEVEL, heat));
		notifyUpdate();
	}

	private boolean tryUpdateLiquid(ContainerItemContext context, TransactionContext t) {
		Storage<FluidVariant> handler = context.find(FluidStorage.ITEM);
		if (handler == null)
			return false;

		if (tankInventory == null)
			return false;

		Predicate<FluidVariant> filter = fluidVariant -> find(new FluidStack(fluidVariant, 1), level).isPresent();

		long moved = StorageUtil.move(handler, tankInventory, filter, Long.MAX_VALUE, t);
		if(moved > 0) {
			level.playSound(null, getBlockPos(), SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, .125f + level.random.nextFloat() * .125f, .75f - level.random.nextFloat() * .25f);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * @return true if the heater updated its burn time, items are auto-consumed!
	 */
	protected boolean tryUpdateFuel(ItemStack itemStack, ContainerItemContext context, TransactionContext t, boolean forceOverflow) {
		if (isCreative)
			return false;

		FuelType newFuel = FuelType.NONE;
		int newBurnTime;

		// Liquid Fluid Logic
		if(tryUpdateLiquid(context, t))
			return true;

		if (AllItemTags.BLAZE_BURNER_FUEL_SPECIAL.matches(itemStack)) {
			newBurnTime = 1000;
			newFuel = FuelType.SPECIAL;
		} else {
			var burn = FuelRegistry.INSTANCE.get(itemStack.getItem());
			newBurnTime = burn != null ? burn : 0;
			if (newBurnTime > 0)
				newFuel = FuelType.NORMAL;
			else if (AllItemTags.BLAZE_BURNER_FUEL_REGULAR.matches(itemStack)) {
				newBurnTime = 1600; // Same as coal
				newFuel = FuelType.NORMAL;
			}
		}

		if (newFuel == FuelType.NONE) return false;
		if (newFuel.ordinal() < activeFuel.ordinal()) return false;
		if (activeFuel == FuelType.SPECIAL && remainingBurnTime > 20) return false;

		if (newFuel == activeFuel) {
			if (remainingBurnTime + newBurnTime > MAX_HEAT_CAPACITY && !forceOverflow) return false;
			newBurnTime = Mth.clamp(remainingBurnTime + newBurnTime, 0, MAX_HEAT_CAPACITY);
		}
		context.extract(context.getItemVariant(), 1, t);

		int finalNewBurnTime = newBurnTime;
		FuelType finalNewFuel = newFuel;
		TransactionCallback.onSuccess(t, () -> {
			activeFuel = finalNewFuel;
			remainingBurnTime = finalNewBurnTime;

			if (level.isClientSide) {
				spawnParticleBurst(activeFuel == FuelType.SPECIAL);
				return;
			}

			BlazeBurnerBlock.HeatLevel prev = getHeatLevelFromBlock();
			playSound();
			updateBlockState();

			if (prev != getHeatLevelFromBlock())
				level.playSound(null, worldPosition, SoundEvents.BLAZE_AMBIENT, SoundSource.BLOCKS,
						.125f + level.random.nextFloat() * .125f, 1.15f - level.random.nextFloat() * .25f);
        });

		return true;
	}

	protected void applyCreativeFuel() {
		activeFuel = FuelType.NONE;
		remainingBurnTime = 0;
		isCreative = true;

		BlazeBurnerBlock.HeatLevel next = getHeatLevelFromBlock().nextActiveLevel();

		if (level.isClientSide) {
			spawnParticleBurst(next.isAtLeast(BlazeBurnerBlock.HeatLevel.SEETHING));
			return;
		}

		playSound();
		if (next == BlazeBurnerBlock.HeatLevel.FADING)
			next = next.nextActiveLevel();
		setBlockHeat(next);
	}

	public boolean isCreativeFuel(ItemStack stack) {
		return AllItems.CREATIVE_BLAZE_CAKE.isIn(stack);
	}

	public boolean isValidBlockAbove() {
		BlockState blockState = level.getBlockState(worldPosition.above());
		return AllBlocks.BASIN.has(blockState) || blockState.getBlock() instanceof FluidTankBlock;
	}

	protected void playSound() {
		level.playSound(null, worldPosition, SoundEvents.BLAZE_SHOOT, SoundSource.BLOCKS,
			.125f + level.random.nextFloat() * .125f, .75f - level.random.nextFloat() * .25f);
	}

	protected BlazeBurnerBlock.HeatLevel getHeatLevelFromFuelType(FuelType fuel) {
		BlazeBurnerBlock.HeatLevel level = BlazeBurnerBlock.HeatLevel.SMOULDERING;
		switch (fuel) {
		case SPECIAL:
			level = BlazeBurnerBlock.HeatLevel.SEETHING;
			break;
		case NORMAL:
			boolean lowPercent = (double) remainingBurnTime / MAX_HEAT_CAPACITY < 0.0125;
			level = lowPercent ? BlazeBurnerBlock.HeatLevel.FADING : BlazeBurnerBlock.HeatLevel.KINDLED;
			break;
		default:
		case NONE:
			break;
		}
		return level;
	}

	protected void spawnParticles(BlazeBurnerBlock.HeatLevel heatLevel, double burstMult) {
		if (level == null)
			return;
		if (heatLevel == BlazeBurnerBlock.HeatLevel.NONE)
			return;

		RandomSource r = level.getRandom();

		Vec3 c = VecHelper.getCenterOf(worldPosition);
		Vec3 v = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .125f)
			.multiply(1, 0, 1));

		if (r.nextInt(3) == 0)
			level.addParticle(ParticleTypes.LARGE_SMOKE, v.x, v.y, v.z, 0, 0, 0);
		if (r.nextInt(2) != 0)
			return;

		boolean empty = level.getBlockState(worldPosition.above())
			.getCollisionShape(level, worldPosition.above())
			.isEmpty();

		double yMotion = empty ? .0625f : r.nextDouble() * .0125f;
		Vec3 v2 = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .5f)
			.multiply(1, .25f, 1)
			.normalize()
			.scale((empty ? .25f : .5) + r.nextDouble() * .125f))
			.add(0, .5, 0);

		if (heatLevel.isAtLeast(BlazeBurnerBlock.HeatLevel.SEETHING)) {
			level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, v2.x, v2.y, v2.z, 0, yMotion, 0);
		} else if (heatLevel.isAtLeast(BlazeBurnerBlock.HeatLevel.FADING)) {
			level.addParticle(ParticleTypes.FLAME, v2.x, v2.y, v2.z, 0, yMotion, 0);
		}
		return;
	}

	public void spawnParticleBurst(boolean soulFlame) {
		Vec3 c = VecHelper.getCenterOf(worldPosition);
		RandomSource r = level.random;
		for (int i = 0; i < 20; i++) {
			Vec3 offset = VecHelper.offsetRandomly(Vec3.ZERO, r, .5f)
				.multiply(1, .25f, 1)
				.normalize();
			Vec3 v = c.add(offset.scale(.5 + r.nextDouble() * .125f))
				.add(0, .125, 0);
			Vec3 m = offset.scale(1 / 32f);

			level.addParticle(soulFlame ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, v.x, v.y, v.z, m.x, m.y,
				m.z);
		}
	}

	public enum FuelType {
		NONE, NORMAL, SPECIAL
	}

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		ObservePacket.send(worldPosition, 0);
		return containedFluidTooltip(tooltip, isPlayerSneaking, this.getFluidStorage(null));
	}

	@Override
	public void onObserved(ServerPlayer player, ObservePacket pack) {
		notifyUpdate();
	}
}
