package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.ShapeStore;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import mtr.data.Rail;
import mtr.data.RailwayData;

import java.util.Map;

/**
 * 暴露 {@code RailwayData} 的私有轨道映射（{@code rails}，{@code 节点 → 节点 → 轨道}）。
 *
 * <p>该字段自 MTR 3.0 起在 YMTR 所有 3.x 版本上同名同构；公开读取方法
 * {@code getRailsMap()} 直到 YMTR 3.6.2.2 才出现，因此线型工具统一经本访问器获取
 * 轨道映射，兼容全部支持版本。</p>
 */
@Mixin(RailwayData.class)
public interface RailwayDataAccessor extends ShapeStore.RailwayDataAccessorDuck {

        @Accessor(value = "rails", remap = false)
        @Override
        Map<BlockPos, Map<BlockPos, Rail>> yanyang$getRails();
}
