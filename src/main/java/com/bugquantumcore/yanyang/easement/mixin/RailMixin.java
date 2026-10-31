package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.ClassicRailGeometry;
import com.bugquantumcore.yanyang.easement.EasedRailPath;
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

import java.util.Map;

/**
 * 将两种缓和曲线线型（三次抛物线 / 半波正弦）注入 MTR 3.x 的 {@link Rail}。
 *
 * <p>这是 MTR-Yanyang {@code tsc-1.21.x} 分支中 {@code RailMath} 对
 * {@code Rail.Shape.CUBIC_PARABOLA / HALF_WAVE_SINE} 处理逻辑在 MTR 3.x 架构下的
 * 等价实现。区别在于：TSC 在构造 {@code RailMath} 时按 {@code Rail.Shape} 求解缓和
 * 路径；而 MTR 3.x 的 {@code Rail} 在构造函数中就把几何烘焙为经典「直线+圆弧」分段，
 * 因此本补丁改为<b>在几何被查询时惰性生效</b>：</p>
 * <ul>
 * <li>当 {@code railData} 含有 {@code rail_shape=cubic_parabola|half_wave_sine} 时，
 * 把阴影的经典几何字段打包为 {@link ClassicRailGeometry}，从中复原两节点（方块坐标 +
 * 22.5° 网格方向角）并交由移植的 {@link EasedRailPath} 求解缓和路径；</li>
 * <li>{@code getPosition} / {@code getLength} / {@code render} / {@code goodRadius}
 * 在缓和路径存在时改用采样表，列车的移动、信号、渲染、桥隧修建等所有下游消费方
 * 因此自动沿缓和曲线工作；</li>
 * <li>缓和曲线无法表达的几何（求解失败）自动回退经典分段，线型永远是可选增强；</li>
 * <li>不改任何序列化格式 —— 线型标记由 YMTR 原生的 {@code railData} 键值对承载，
 * 随存档保存、随网络同步。</li>
 * </ul>
 *
 * <p><b>注入目标全部按方法名匹配（{@code remap = false} 且不写描述符）</b>：
 * 这些方法都是 MTR 的模组方法（生产环境中不参与混淆重映射），而描述符中出现的
 * Minecraft 类型（如 {@code Vec3}）在开发与生产环境分别使用命名/中间映射名 —— 按名
 * 匹配可以同时适配两种环境，也天然规避方法重载问题（各目标方法均无重载）。</p>
 *
 * <p><b>惰性求值的线程与构造顺序说明</b>：反序列化构造函数在计算 {@code facingStart}
 * / {@code facingEnd} 时就会经由 {@code getRailAngle} 触发首次几何查询，此时
 * {@code facingStart} 字段尚未赋值，因此缓和路径的方向角<b>不读取该字段</b>，而是按
 * {@code getRailAngle} 的原逻辑从经典几何重新推导（并量化到 22.5° 网格，见
 * {@link ClassicRailGeometry}）。缓和路径的端点方向角由构造精确等于请求值，因此两种
 * 触发顺序得到完全一致的结果，{@code isValid()} 的方向角校验也始终成立。</p>
 */
@Mixin(Rail.class)
public abstract class RailMixin {

	// ---- 经典求解器留下的几何字段（@Shadow 读取，绝不写入） ----

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
	private Map<String, String> railData;

	/**
	 * 竖曲线（Y 剖面）沿用 MTR 原版逻辑：缓急坡 / 缆车垂度均按缓和后的总长度计算。
	 */
	@Shadow(remap = false)
	private double getPositionY(double value) {
		throw new AssertionError();
	}

	// ---- 缓和路径的惰性缓存 ----

	@Unique
	private EasedRailPath yanyang$easedPath;
	@Unique
	private boolean yanyang$easedResolved;

	/**
	 * @return 当前轨道的缓和路径；未设置线型或几何不可表示时为 {@code null}。
	 * 结果缓存；{@code railData} 变化后由 {@link #yanyang$onSetRailData} 失效。
	 */
	@Unique
	private EasedRailPath yanyang$easedPath() {
		if (!yanyang$easedResolved) {
			yanyang$easedResolved = true;
			yanyang$easedPath = yanyang$buildEasedPath();
		}
		return yanyang$easedPath;
	}

	/**
	 * 把经典几何字段打包为 {@link ClassicRailGeometry} 并求解缓和路径。
	 */
	@Unique
	private EasedRailPath yanyang$buildEasedPath() {
		return new ClassicRailGeometry(
				h1, k1, r1, tStart1, tEnd1,
				h2, k2, r2, tStart2, tEnd2,
				reverseT1, reverseT2, isStraight1, isStraight2
		).resolveEased(railData);
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

	// ---- 缓存失效 ----

	/**
	 * 轨道附加数据变化（轨道数据编辑器保存、本补丁的线型工具切换等）后重建缓和路径。
	 */
	@Inject(method = "setRailData", at = @At("TAIL"), remap = false)
	private void yanyang$onSetRailData(Map<String, String> newRailData, CallbackInfo ci) {
		yanyang$easedPath = null;
		yanyang$easedResolved = false;
	}
}
