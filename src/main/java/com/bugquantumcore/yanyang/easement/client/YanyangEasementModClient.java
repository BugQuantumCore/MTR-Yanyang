package com.bugquantumcore.yanyang.easement.client;

import com.bugquantumcore.yanyang.easement.RailPair;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端入口（legacy YMTR 3.4.4 ~ 3.6.1.2 的客户端同步）。
 *
 * <p>注册 {@code yanyang_easement:shape_sync} 的 S2C 接收器，把服务端发来的线型真值
 * 写入客户端镜像（{@link ShapeStore}），并注入「当前客户端维度」供给 —— 两者共同让
 * 客户端轨道（由 YMTR 网络包重建、不携带印记）能按「维度 × 连接键」解析线型。</p>
 */
@Environment(EnvType.CLIENT)
public class YanyangEasementModClient implements ClientModInitializer {

        @Override
        public void onInitializeClient() {
                ShapeStore.setClientDimensionSupplier(() -> {
                        final Minecraft client = Minecraft.getInstance();
                        return client.level == null ? null : client.level.dimension().location();
                });

                ClientPlayNetworking.registerGlobalReceiver(ShapeStore.SHAPE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
                        // 接收器在网络线程执行：先读取全部载荷，再跳到客户端线程写镜像。
                        final ResourceLocation dimension = buf.readResourceLocation();
                        final long a = buf.readLong();
                        final long b = buf.readLong();
                        final String shapeKey = buf.readUtf();
                        client.execute(() -> ShapeStore.clientPut(dimension, new RailPair(a, b), shapeKey.isEmpty() ? null : shapeKey));
                });
        }
}
