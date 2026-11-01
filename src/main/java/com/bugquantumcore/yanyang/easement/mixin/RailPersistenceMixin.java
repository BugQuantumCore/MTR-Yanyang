package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.RailDuck;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import org.msgpack.core.MessagePacker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import mtr.data.Rail;

import java.util.Map;

/**
 * 线型印记的存档持久化（<b>仅 legacy YMTR 3.4.4 ~ 3.6.1.2</b>，由
 * {@code YanyangMixinPlugin} 按能力加载）。
 *
 * <p>MTR 3.x 把整份铁路数据以 MessagePack 保存：{@code RailwayData.writeMessagePackDataset}
 * 对每个条目先写 {@code packMapHeader(item.messagePackLength())}（条目数即映射长度），
 * 再调用 {@code item.toMessagePack(packer)} 逐键写入。本 Mixin 在两端搭接线型印记：</p>
 * <ul>
 * <li>{@code messagePackLength}：有印记时返回值 +1（多一个映射条目）；</li>
 * <li>{@code toMessagePack}：末尾追加 {@code packString("yanyang_shape").packString(印记)}；</li>
 * <li>{@code Rail(Map)} 构造器（世界加载时反序列化）：末尾读回印记写入轨道实例。</li>
 * </ul>
 * <p>YMTR 自身在 3.6.2.2+ 上以完全相同的方式搭载 {@code railData}（{@code KEY_RAIL_DATA}），
 * 因此该持久化通道在 3.x 全系上是既定结构。印记是普通的字符串键值对，
 * 卸载本补丁后 YMTR 反序列化时按未知键忽略，存档无损。</p>
 *
 * <p><b>注入目标说明</b>：三个方法均无重载；{@code Rail(Map)} 的描述符只含 Java 类型
 * （{@code (Ljava/util/Map;)V}），开发与生产环境字面一致，可安全写死。</p>
 */
@Mixin(Rail.class)
public abstract class RailPersistenceMixin {

        /**
         * 存档写入：在映射末尾追加线型印记条目。
         */
        @Inject(method = "toMessagePack", at = @At("TAIL"), remap = false)
        private void yanyang$packShapeStamp(MessagePacker packer, CallbackInfo ci) throws java.io.IOException {
                final String stamp = ((RailDuck) this).yanyang$getShapeStamp();
                if (stamp != null) {
                        packer.packString(ShapeStore.LEGACY_KEY).packString(stamp);
                }
        }

        /**
         * 映射条目数：有印记时比经典多一个（{@code writeMessagePackDataset} 以此为
         * {@code packMapHeader} 计数）。
         */
        @Inject(method = "messagePackLength", at = @At("RETURN"), remap = false)
        private void yanyang$packShapeStampLength(CallbackInfoReturnable<Integer> cir) {
                if (((RailDuck) this).yanyang$getShapeStamp() != null) {
                        cir.setReturnValue(cir.getReturnValueI() + 1);
                }
        }

        /**
         * 存档读回（世界加载 / 反序列化）：把印记写到轨道实例上。
         * 印记在构造完成前写入，因此构造后的首次几何查询即可命中。
         */
        @Inject(method = "<init>(Ljava/util/Map;)V", at = @At("TAIL"), remap = false)
        private void yanyang$readShapeStamp(Map<?, ?> map, CallbackInfo ci) {
                final Object value = map == null ? null : map.get(ShapeStore.LEGACY_KEY);
                if (value instanceof String key) {
                        ((RailDuck) this).yanyang$setShapeStamp(key);
                }
        }
}
