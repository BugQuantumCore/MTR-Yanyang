package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.AnteMirror;
import com.bugquantumcore.yanyang.easement.ClassicRailGeometry;
import com.bugquantumcore.yanyang.easement.EasedRailPath;
import com.bugquantumcore.yanyang.easement.EasementShape;
import com.bugquantumcore.yanyang.easement.RailDataDuck;
import com.bugquantumcore.yanyang.easement.RailDuck;
import com.bugquantumcore.yanyang.easement.RailPair;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import com.bugquantumcore.yanyang.easement.YanyangCompat;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import mtr.data.Rail;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 将两种缓和曲线线型（三次抛物线 / 半波正弦）注入 MTR 3.x 的 {@link Rail}。
 *
 * <p>这是 MTR-Yanyang {@code tsc-1.21.x} 分支中 {@code RailMath} 对
 * {@code Rail.Shape.CUBIC_PARABOLA / HALF_WAVE_SINE} 处理逻辑在 MTR 3.x 架构下的
 * 等价实现。区别在于：TSC 在构造 {@code RailMath} 时按 {@code Rail.Shape} 求解缓和
 * 路径；而 MTR 3.x 的 {@code Rail} 在构造函数中就把几何烘焙为经典「直线+圆弧」分段，
 * 因此本补丁改为<b>在几何被查询时惰性生效</b>：</p>
 * <ul>
 * <li>线型解析顺序：YMTR 3.6.2.2+（具备 {@code railData}，由
 * {@code RailDataCarrierMixin} 实现的 {@link RailDataDuck} 提供）→ 附加数据
 * {@code rail_shape}；legacy YMTR（3.4.4 ~ 3.6.1.2）→ 轨道实例印记
 * （{@link RailDuck}，存档搭载 + 工具写入）→ 客户端镜像
 * （{@link ShapeStore}，按「维度 × 连接键」，空串条目 = 明确的经典线型）→
 * ANTE {@code customConfigs} 的 {@code rail_shape} 键（轨道配置编辑器路径，
 * 覆盖被 MTR-ANTE 分支的 {@code PacketUpdateRail} 用新实例替换后印记丢失的
 * 场景）；全部为空 = 经典线型。</li>
 * <li>{@code getPosition} / {@code getLength} / {@code render} / {@code goodRadius}
 * 在缓和路径存在时改用采样表，列车的移动、信号、渲染、桥隧修建等所有下游消费方
 * 因此自动沿缓和曲线工作；</li>
 * <li>缓和曲线无法表达的几何（求解失败）自动回退经典分段，线型永远是可选增强；</li>
 * <li>缓和路径按「失效纪元 + 实例印记」缓存：任何线型数据变化都会递增
 * {@link ShapeStore#epoch}，下次查询即重建。</li>
 * </ul>
 *
 * <p><b>本 Mixin 的注入目标在 YMTR 3.4.4 ~ 3.6.3 上完全一致</b>（几何字段
 * {@code h1..isStraight2} 与四个查询方法自 MTR 3.0 起稳定），因此无条件应用；
 * 版本差异全部收敛到条件 Mixin（{@code RailPersistenceMixin} /
 * {@code RailDataCarrierMixin}，由 {@code YanyangMixinPlugin} 按能力加载）。</p>
 *
 * <p><b>注入目标全部按方法名匹配（{@code remap = false} 且不写描述符）</b>：
 * 这些方法都是 MTR 的模组方法（生产环境中不参与混淆重映射），而描述符中出现的
 * Minecraft 类型（如 {@code Vec3}）在开发与生产环境分别使用命名/中间映射名 —— 按名
 * 匹配可以同时适配两种环境，也天然规避方法重载问题（各目标方法均无重载）。</p>
 *
 * <p><b>惰性求值的线程与构造顺序说明</b>：反序列化构造函数在计算 {@code facingStart}
 * / {@code facingEnd} 时就会经由 {@code getRailAngle} 触发首次几何查询，此时
 * {@code facingStart} 字段尚未赋值，因此缓和路径的方向角<b>不读取该字段</b>，而是按
 * {@code getRailAngle} 的原逻辑从经典几何重新推导（保留真实角度、不量化，见
 * {@link ClassicRailGeometry}；{@code getRailAngle} 自身经 {@code RailAngle.fromAngle}
 * 的量化不受影响，因此 {@code isValid()} 的方向角校验始终成立，方速万向节点的
 * 任意角度轨道也能被正确求解）。缓和路径的端点方向角由构造精确等于请求值，因此两种
 * 触发顺序得到完全一致的结果。</p>
 */
@Mixin(Rail.class)
public abstract class RailMixin implements RailDuck {

        // ---- 经典求解器留下的几何字段（@Shadow 读取，绝不写入；YMTR 3.0 起稳定） ----

        @Shadow(remap = false)
        private double h1;
        @Shadow(remap = false)
        private double k1;
        @Shadow(remap = false)
        private double r1;
        @Shadow(remap = false)
        private double tStart1;
        @Shadow(remap = false)
        private double tEnd1;
        @Shadow(remap = false)
        private double h2;
        @Shadow(remap = false)
        private double k2;
        @Shadow(remap = false)
        private double r2;
        @Shadow(remap = false)
        private double tStart2;
        @Shadow(remap = false)
        private double tEnd2;
        @Shadow(remap = false)
        private boolean reverseT1;
        @Shadow(remap = false)
        private boolean reverseT2;
        @Shadow(remap = false)
        private boolean isStraight1;
        @Shadow(remap = false)
        private boolean isStraight2;
        @Shadow(remap = false)
        private int yStart;
        @Shadow(remap = false)
        private int yEnd;

        /**
         * 竖曲线（Y 剖面）沿用 MTR 原版逻辑：缓急坡 / 缆车垂度均按缓和后的总长度计算。
         */
        @Shadow(remap = false)
        private double getPositionY(double value) {
                throw new AssertionError();
        }

        // ---- 缓和路径的惰性缓存（按失效纪元） ----

        @Unique
        private EasedRailPath yanyang$easedPath;
        @Unique
        private boolean yanyang$easedResolved;
        @Unique
        private long yanyang$easedEpoch;
        @Unique
        private String yanyang$shapeStamp;

        @Override
        public String yanyang$getShapeStamp() {
                return yanyang$shapeStamp;
        }

        @Override
        public void yanyang$setShapeStamp(String shapeKey) {
                yanyang$shapeStamp = EasementShape.fromKey(shapeKey) == null ? null : shapeKey;
                yanyang$easedPath = null;
                yanyang$easedResolved = false;
                ShapeStore.bumpEpoch();
        }

        /**
         * @return 当前轨道的缓和路径；未设置线型或几何不可表示时为 {@code null}。
         * 结果按失效纪元缓存：任何线型数据变化后重建。
         */
        @Unique
        private EasedRailPath yanyang$easedPath() {
                if (!yanyang$easedResolved || yanyang$easedEpoch != ShapeStore.epoch()) {
                        yanyang$easedResolved = true;
                        yanyang$easedEpoch = ShapeStore.epoch();
                        yanyang$easedPath = yanyang$buildEasedPath();
                }
                return yanyang$easedPath;
        }

        /**
         * 按当前线型求解缓和路径：YMTR 3.6.2.2+ 读轨道附加数据；legacy 读实例印记，
         * 未命中时查客户端镜像（空串条目 = 明确的经典线型，不再回退），最后回退
         * ANTE {@code customConfigs}（轨道配置编辑器路径）。
         */
        @Unique
        private EasedRailPath yanyang$buildEasedPath() {
                if (YanyangCompat.RAILDATA_CAPABLE && (Object) this instanceof RailDataDuck duck) {
                        return yanyang$resolveEased(EasementShape.fromRailData(duck.yanyang$getRailData()), "railData");
                }
                final String stamp = yanyang$shapeStamp;
                if (stamp != null) {
                        ShapeStore.noteResolution("stamp");
                        return yanyang$resolveEased(EasementShape.fromKey(stamp), stamp);
                }
                // legacy 客户端轨道：按「维度 × 连接键」查镜像。空串条目 = 明确的经典线型。
                final ClassicRailGeometry geometry = yanyang$geometry();
                final RailPair pair = yanyang$pairKey(geometry);
                final String mirrored = ShapeStore.clientLookup(pair);
                if (mirrored != null) {
                        if (mirrored.isEmpty()) {
                                ShapeStore.noteResolution("classic");
                                return null;
                        }
                        ShapeStore.noteResolution("mirror");
                        return yanyang$resolveEased(EasementShape.fromKey(mirrored), mirrored);
                }
                // ANTE 轨道配置编辑器路径 / 被编辑器替换后印记丢失的兜底。
                final String configured = AnteMirror.readRailShapeKey(this);
                if (configured != null) {
                        ShapeStore.noteResolution("configs");
                        return yanyang$resolveEased(EasementShape.fromKey(configured), configured);
                }
                ShapeStore.noteMiss(pair);
                return null;
        }

        /**
         * 从经典几何字段打包几何快照并求解缓和路径。求解失败计入诊断
         * （线型已知但两族曲线无法表达时回退经典线型）。
         */
        @Unique
        private EasedRailPath yanyang$resolveEased(EasementShape shape, String shapeKey) {
                if (shape == null) {
                        return null;
                }
                final EasedRailPath path = yanyang$geometry().resolveEased(shape);
                if (path == null) {
                        ShapeStore.noteSolveFailure(shapeKey);
                }
                return path;
        }

        @Unique
        private ClassicRailGeometry yanyang$geometry() {
                return new ClassicRailGeometry(
                                h1, k1, r1, tStart1, tEnd1,
                                h2, k2, r2, tStart2, tEnd2,
                                reverseT1, reverseT2, isStraight1, isStraight2
                );
        }

        /**
         * 客户端镜像查询用的连接键：从经典几何复原两端节点（含 Y），与
         * {@code BlockPos.asLong()} 打包格式一致。
         */
        @Unique
        private RailPair yanyang$pairKey(ClassicRailGeometry geometry) {
                final double length = geometry.length();
                final double[] start = geometry.positionXZ(0);
                final double[] end = geometry.positionXZ(length);
                return RailPair.of(
                                RailPair.pack((long) Math.floor(start[0]), yStart, (long) Math.floor(start[1])),
                                RailPair.pack((long) Math.floor(end[0]), yEnd, (long) Math.floor(end[1]))
                );
        }

        /**
         * 缓和路径上指定弧长处的 XZ 位置，可沿局部法线（路径右侧）偏移。
         * 与 TSC {@code RailMath.easedPosition} 的插值逻辑一致。
         */
        @Unique
        private Vec3 yanyang$easedPosition(EasedRailPath eased, double value, double radiusOffset) {
                final double position = Mth.clamp(value / eased.step, 0, eased.x.length - 1);
                final int sample = (int) Math.min(Math.floor(position), eased.x.length - 2);
                final double ratio = position - sample;
                final double x = eased.x[sample] + (eased.x[sample + 1] - eased.x[sample]) * ratio;
                final double z = eased.z[sample] + (eased.z[sample + 1] - eased.z[sample]) * ratio;
                if (radiusOffset == 0) {
                        return new Vec3(x, 0, z);
                }
                final double heading = eased.heading[sample] + (eased.heading[sample + 1] - eased.heading[sample]) * ratio;
                return new Vec3(x + radiusOffset * Math.sin(heading), 0, z - radiusOffset * Math.cos(heading));
        }

        // ---- 几何查询拦截 ----

        @Inject(method = "getPosition", at = @At("HEAD"), cancellable = true, remap = false)
        private void yanyang$easedGetPosition(double rawValue, CallbackInfoReturnable<Vec3> cir) {
                final EasedRailPath eased = yanyang$easedPath();
                if (eased == null) {
                        return;
                }
                final double value = Mth.clamp(rawValue, 0, eased.length);
                final double y = getPositionY(value);
                final Vec3 position = yanyang$easedPosition(eased, value, 0);
                cir.setReturnValue(new Vec3(position.x, y, position.z));
        }

        @Inject(method = "getLength", at = @At("HEAD"), cancellable = true, remap = false)
        private void yanyang$easedGetLength(CallbackInfoReturnable<Double> cir) {
                final EasedRailPath eased = yanyang$easedPath();
                if (eased != null) {
                        cir.setReturnValue(eased.length);
                }
        }

        /**
         * 按 MTR 3.x 的回调签名（四角 XZ + 两端 Y，逐段发射）遍历缓和采样表，
         * 与经典 {@code renderSegment} 的发射粒度（约每方块一段）保持一致，
         * 使轨道贴图密度与经典轨道相同。
         */
        @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
        private void yanyang$easedRender(Rail.RenderRail callback, float offsetRadius1, float offsetRadius2, CallbackInfo ci) {
                final EasedRailPath eased = yanyang$easedPath();
                if (eased == null) {
                        return;
                }
                ci.cancel();

                final double count = eased.length;
                final double increment = count / Math.max(1, Math.round(count));

                for (double i = 0; i < count - 0.1; i += increment) {
                        final double d1 = i;
                        final double d2 = Math.min(i + increment, count);

                        final Vec3 corner1 = yanyang$easedPosition(eased, d1, offsetRadius1);
                        final Vec3 corner2 = yanyang$easedPosition(eased, d1, offsetRadius2);
                        final Vec3 corner3 = yanyang$easedPosition(eased, d2, offsetRadius2);
                        final Vec3 corner4 = yanyang$easedPosition(eased, d2, offsetRadius1);

                        final double y1 = getPositionY(d1);
                        final double y2 = getPositionY(d2);

                        callback.renderRail(corner1.x, corner1.z, corner2.x, corner2.z, corner3.x, corner3.z, corner4.x, corner4.z, y1, y2);
                }
        }

        /**
         * 缓和曲线的半径由其自身的曲率剖面决定（采样表中已给出两半段最小半径）；
         * 经典的「最小半径 2 方块」约束只针对圆弧分段，不适用于缓和曲线族。
         */
        @Inject(method = "goodRadius", at = @At("HEAD"), cancellable = true, remap = false)
        private void yanyang$easedGoodRadius(CallbackInfoReturnable<Boolean> cir) {
                if (yanyang$easedPath() != null) {
                        cir.setReturnValue(true);
                }
        }
}
