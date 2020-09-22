package com.simibubi.create.content.contraptions.components.structureMovement.bearing;

import net.minecraft.block.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import com.simibubi.create.content.contraptions.base.KineticTileEntity;
import com.simibubi.create.content.contraptions.components.structureMovement.Contraption;
import com.simibubi.create.content.contraptions.components.structureMovement.ContraptionEntity;
import com.simibubi.create.content.contraptions.components.structureMovement.bearing.ClockworkContraption.HandType;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.Direction.AxisDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;

public class ClockworkBearingTileEntity extends KineticTileEntity implements IBearingTileEntity {

	protected ContraptionEntity hourHand;
	protected ContraptionEntity minuteHand;
	protected float hourAngle;
	protected float minuteAngle;
	protected float clientHourAngleDiff;
	protected float clientMinuteAngleDiff;

	protected boolean running;
	protected boolean assembleNextTick;

	public ClockworkBearingTileEntity(TileEntityType<? extends ClockworkBearingTileEntity> type) {
		super(type);
		setLazyTickRate(3);
	}

	@Override
	public void tick() {
		super.tick();

		if (world.isRemote) {
			clientMinuteAngleDiff /= 2;
			clientHourAngleDiff /= 2;
		}

		if (running && Contraption.isFrozen())
			disassemble();

		if (!world.isRemote && assembleNextTick) {
			assembleNextTick = false;
			if (running) {
				boolean canDisassemble = true;
				if (speed == 0 && (canDisassemble || hourHand == null || hourHand.getContraption().blocks.isEmpty())) {
					if (hourHand != null)
						hourHand.getContraption().stop(world);
					if (minuteHand != null)
						minuteHand.getContraption().stop(world);
					disassemble();
				}
				return;
			} else
				assemble();
			return;
		}

		if (!running)
			return;

		if (!(hourHand != null && hourHand.isStalled())) {
			float newAngle = hourAngle + getHourArmSpeed();
			hourAngle = (float) (newAngle % 360);
		}

		if (!(minuteHand != null && minuteHand.isStalled())) {
			float newAngle = minuteAngle + getMinuteArmSpeed();
			minuteAngle = (float) (newAngle % 360);
		}

		applyRotations();
	}

	protected void applyRotations() {
		Axis axis = getBlockState().get(BlockStateProperties.FACING).getAxis();
		Direction direction = Direction.getFacingFromAxis(AxisDirection.POSITIVE, axis);
		Vector3d directionVec = Vector3d.of(direction.getDirectionVec());
		if (hourHand != null) {
			Vector3d vec = new Vector3d(1, 1, 1).scale(hourAngle).mul(directionVec);
			hourHand.rotateTo(vec.x, vec.y, vec.z);
		}
		if (minuteHand != null) {
			Vector3d vec = new Vector3d(1, 1, 1).scale(minuteAngle).mul(directionVec);
			minuteHand.rotateTo(vec.x, vec.y, vec.z);
		}
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (hourHand != null && !world.isRemote)
			sendData();
	}

	public float getHourArmSpeed() {
		float speed = getAngularSpeed() / 2f;

		if (speed != 0) {
			int dayTime = (int) (world.getDayTime() % 24000);
			int hours = (dayTime / 1000 + 6) % 24;
			int offset = getBlockState().get(ClockworkBearingBlock.FACING).getAxisDirection().getOffset();
			float hourTarget = (float) (offset * -360 / 12f * (hours % 12));
			float shortestAngleDiff = AngleHelper.getShortestAngleDiff(hourAngle, hourTarget);
			if (shortestAngleDiff < 0) {
				speed = Math.max(speed, shortestAngleDiff);
			} else {
				speed = Math.min(-speed, shortestAngleDiff);
			}
		}

		return speed + clientHourAngleDiff / 3f;
	}

	public float getMinuteArmSpeed() {
		float speed = getAngularSpeed();

		if (speed != 0) {
			int dayTime = (int) (world.getDayTime() % 24000);
			int minutes = (dayTime % 1000) * 60 / 1000;
			int offset = getBlockState().get(ClockworkBearingBlock.FACING).getAxisDirection().getOffset();
			float minuteTarget = (float) (offset * -360 / 60f * (minutes));
			float shortestAngleDiff = AngleHelper.getShortestAngleDiff(minuteAngle, minuteTarget);
			if (shortestAngleDiff < 0) {
				speed = Math.max(speed, shortestAngleDiff);
			} else {
				speed = Math.min(-speed, shortestAngleDiff);
			}
		}

		return speed + clientMinuteAngleDiff / 3f;
	}

	public float getAngularSpeed() {
		float speed = -Math.abs(getSpeed() * 3 / 10f);
		if (world.isRemote)
			speed *= ServerSpeedProvider.get();
		return speed;
	}

	public void assemble() {
		if (!(world.getBlockState(pos).getBlock() instanceof ClockworkBearingBlock))
			return;

		Direction direction = getBlockState().get(BlockStateProperties.FACING);

		// Collect Construct
		Pair<ClockworkContraption, ClockworkContraption> contraption =
			ClockworkContraption.assembleClockworkAt(world, pos, direction);
		if (contraption == null)
			return;
		if (contraption.getLeft() == null)
			return;
		if (contraption.getLeft().blocks.isEmpty())
			return;
		BlockPos anchor = pos.offset(direction);

		contraption.getLeft().removeBlocksFromWorld(world, BlockPos.ZERO);
		hourHand = ContraptionEntity.createStationary(world, contraption.getLeft()).controlledBy(this);
		hourHand.setPosition(anchor.getX(), anchor.getY(), anchor.getZ());
		world.addEntity(hourHand);

		if (contraption.getRight() != null) {
			anchor = pos.offset(direction, contraption.getRight().offset + 1);
			contraption.getRight().removeBlocksFromWorld(world, BlockPos.ZERO);
			minuteHand = ContraptionEntity.createStationary(world, contraption.getRight()).controlledBy(this);
			minuteHand.setPosition(anchor.getX(), anchor.getY(), anchor.getZ());
			world.addEntity(minuteHand);
		}

		// Run
		running = true;
		hourAngle = 0;
		minuteAngle = 0;
		sendData();
	}

	public void disassemble() {
		if (!running && hourHand == null && minuteHand == null)
			return;

		hourAngle = 0;
		minuteAngle = 0;
		applyRotations();

		if (hourHand != null) {
			hourHand.disassemble();
		}
		if (minuteHand != null)
			minuteHand.disassemble();

		hourHand = null;
		minuteHand = null;
		running = false;
		sendData();
	}

	@Override
	public void attach(ContraptionEntity contraption) {
		if (!(contraption.getContraption() instanceof ClockworkContraption))
			return;

		ClockworkContraption cc = (ClockworkContraption) contraption.getContraption();
		markDirty();
		Direction facing = getBlockState().get(BlockStateProperties.FACING);
		BlockPos anchor = pos.offset(facing, cc.offset + 1);
		if (cc.handType == HandType.HOUR) {
			this.hourHand = contraption;
			hourHand.setPosition(anchor.getX(), anchor.getY(), anchor.getZ());
		} else {
			this.minuteHand = contraption;
			minuteHand.setPosition(anchor.getX(), anchor.getY(), anchor.getZ());
		}
		if (!world.isRemote) {
			this.running = true;
			sendData();
		}
	}

	@Override
	public void write(CompoundNBT compound, boolean clientPacket) {
		compound.putBoolean("Running", running);
		compound.putFloat("HourAngle", hourAngle);
		compound.putFloat("MinuteAngle", minuteAngle);
		super.write(compound, clientPacket);
	}

	@Override
	protected void fromTag(BlockState state, CompoundNBT compound, boolean clientPacket) {
		float hourAngleBefore = hourAngle;
		float minuteAngleBefore = minuteAngle;
		
		running = compound.getBoolean("Running");
		hourAngle = compound.getFloat("HourAngle");
		minuteAngle = compound.getFloat("MinuteAngle");
		super.fromTag(state, compound, clientPacket);
		
		if (!clientPacket)
			return;
		
		if (running) {
			clientHourAngleDiff = AngleHelper.getShortestAngleDiff(hourAngleBefore, hourAngle);
			clientMinuteAngleDiff = AngleHelper.getShortestAngleDiff(minuteAngleBefore, minuteAngle);
			hourAngle = hourAngleBefore;
			minuteAngle = minuteAngleBefore;
		} else {
			hourHand = null;
			minuteHand = null;
		}
	}

	@Override
	public void onSpeedChanged(float prevSpeed) {
		super.onSpeedChanged(prevSpeed);
		assembleNextTick = true;
	}

	@Override
	public boolean isValid() {
		return !isRemoved();
	}

	@Override
	public float getInterpolatedAngle(float partialTicks) {
		if (hourHand == null || hourHand.isStalled())
			partialTicks = 0;
		return MathHelper.lerp(partialTicks, hourAngle, hourAngle + getHourArmSpeed());
	}

	@Override
	public void onStall() {
		if (!world.isRemote)
			sendData();
	}

	@Override
	public void remove() {
		if (!world.isRemote)
			disassemble();
		super.remove();
	}

	@Override
	public void collided() {
	}

	@Override
	public boolean isAttachedTo(ContraptionEntity contraption) {
		if (!(contraption.getContraption() instanceof ClockworkContraption))
			return false;
		ClockworkContraption cc = (ClockworkContraption) contraption.getContraption();
		if (cc.handType == HandType.HOUR)
			return this.hourHand == contraption;
		else
			return this.minuteHand == contraption;
	}

	public boolean isRunning() {
		return running;
	}

}
