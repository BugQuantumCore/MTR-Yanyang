package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.RailDataDuck;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mtr.data.Rail;

import java.util.Map;

/**
 * 轨道附加数据载体（<b>仅 YMTR 3.6.2.2+</b>，由 {@code YanyangMixinPlugin} 按能力加载）。
 *
 * <p>YMTR 3.6.2.2 起为 {@code Rail} 增加了轨道附加数据机制（{@code railData} 映射 +
 * {@code setRailData} 方法，随存档 MessagePack 持久化、随 {@code createRailS2C} 同步），
 * 本补丁在该机制上存放线型标记（键 {@code rail_shape}，值
 * {@code cubic_parabola} / {@code half_wave_sine}），并暴露给核心 {@code RailMixin}
 * 读取（经 {@link RailDataDuck}）。</p>
 *
 * <p>{@code setRailData} 注入：轨道数据编辑器保存、本补丁的线型工具切换线型后使
 * 缓和路径缓存失效（递增失效纪元）。</p>
 */
@Mixin(Rail.class)
public abstract class RailDataCarrierMixin implements RailDataDuck {

        @Shadow(remap = false)
        private Map<String, String> railData;

        @Override
        public Map<String, String> yanyang$getRailData() {
                return railData;
        }

        @Inject(method = "setRailData", at = @At("TAIL"), remap = false)
        private void yanyang$onSetRailData(Map<String, String> newRailData, CallbackInfo ci) {
                ShapeStore.bumpEpoch();
        }
}
