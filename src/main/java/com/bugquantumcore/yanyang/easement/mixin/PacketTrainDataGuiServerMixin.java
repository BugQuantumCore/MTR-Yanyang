package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.RailDuck;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mtr.data.Rail;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiServer;

/**
 * 轨道广播的真值同步（<b>仅 legacy YMTR 3.4.4 ~ 3.6.1.2</b>，由
 * {@code YanyangMixinPlugin} 按能力加载）。
 *
 * <p>YMTR 在轨道新建 / 替换 / 修改时都会调用 {@code createRailS2C} 广播该连接的最新
 * 轨道。legacy 版本的网络载荷不含线型印记，客户端镜像可能残留旧值 —— 本 Mixin 在
 * 广播末尾把该连接的服务端真值（印记或 {@code null}）一并发给客户端，使镜像与轨道
 * 实际状态保持一致：</p>
 * <ul>
 * <li>轨道重建（如更换轨型会替换 {@code Rail} 实例、印记随旧实例丢失）→ 广播
 * {@code null}，客户端清除记录 —— 与 capable 版本上 {@code railData} 随重建丢失的
 * 语义一致；</li>
 * <li>轨道原地修改（实例保留、印记仍在）→ 广播印记，客户端记录刷新。</li>
 * </ul>
 *
 * <p>{@code createRailS2C} 在 YMTR 3.4.4 ~ 3.6.3 上签名完全一致且无重载，按方法名
 * 匹配即可；处理器参数会被 loom 正常重映射。</p>
 */
@Mixin(PacketTrainDataGuiServer.class)
public abstract class PacketTrainDataGuiServerMixin {

        @Inject(method = "createRailS2C", at = @At("TAIL"), remap = false)
        private static void yanyang$syncShapeTruth(Level level, TransportMode transportMode, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, long ridingCarNumber, CallbackInfo ci) {
                ShapeStore.broadcastShape(level, pos1, pos2, ((RailDuck) rail1).yanyang$getShapeStamp());
        }
}
