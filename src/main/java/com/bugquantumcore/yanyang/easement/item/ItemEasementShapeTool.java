package com.bugquantumcore.yanyang.easement.item;

import com.bugquantumcore.yanyang.easement.EasementShapeApplier;
import mtr.block.BlockNode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 缓和曲线线型工具：右键轨道节点，循环切换该节点上所有连接的轨道线型
 * （经典 → 三次抛物线 → 半波正弦 → 经典……）；潜行右键反向切换。
 *
 * <p>等价的底层操作也可以用 YMTR 自带的「轨道数据编辑器」完成：
 * 为轨道添加键 {@code rail_shape}、值 {@code cubic_parabola} 或
 * {@code half_wave_sine} 后保存 —— 两种方式写入的数据完全一致。</p>
 */
public class ItemEasementShapeTool extends Item {

	public ItemEasementShapeTool(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("tooltip.yanyang_easement.easement_shape_tool").withStyle(ChatFormatting.GRAY));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level level = context.getLevel();
		if (!(level.getBlockState(context.getClickedPos()).getBlock() instanceof BlockNode)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer player) {
			EasementShapeApplier.cycleShapesAtNode(level, player, context.getClickedPos(), player.isShiftKeyDown());
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
