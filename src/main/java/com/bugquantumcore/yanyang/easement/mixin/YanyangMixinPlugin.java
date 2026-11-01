package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.YanyangCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 按当前 YMTR 能力选择应用哪些 Mixin。
 *
 * <p>YMTR 3.4.4 ~ 3.6.1.2（无 {@code railData}）与 3.6.2.2+（有）各有一组版本相关的
 * Mixin；错误的组合会在类转换阶段崩溃（如 {@code @Shadow} 不存在的字段）：</p>
 *
 * <table border="1">
 * <tr><th>Mixin</th><th>应用条件</th></tr>
 * <tr><td>{@code RailMixin} / {@code RailwayDataAccessor}</td><td>始终（目标在全部支持版本上稳定）</td></tr>
 * <tr><td>{@code RailDataCarrierMixin}</td><td>仅 {@code railData} 版本（3.6.2.2+）</td></tr>
 * <tr><td>{@code RailPersistenceMixin} / {@code PacketTrainDataGuiServerMixin}</td><td>仅 legacy 版本（3.4.4 ~ 3.6.1.2）</td></tr>
 * </table>
 *
 * <p>能力判定见 {@link YanyangCompat}（解析 {@code mtr} 模组版本号，
 * 不反射加载 {@code Rail} 类，避免在 Mixin 应用前触发类定义）。</p>
 */
public class YanyangMixinPlugin implements IMixinConfigPlugin {

        @Override
        public void onLoad(String mixinPackage) {
        }

        @Override
        public String getRefMapperConfig() {
                return null;
        }

        @Override
        public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
                if (mixinClassName.endsWith(".RailDataCarrierMixin")) {
                        return YanyangCompat.RAILDATA_CAPABLE;
                }
                if (mixinClassName.endsWith(".RailPersistenceMixin") || mixinClassName.endsWith(".PacketTrainDataGuiServerMixin")) {
                        return !YanyangCompat.RAILDATA_CAPABLE;
                }
                return true;
        }

        @Override
        public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        }

        @Override
        public List<String> getMixins() {
                return null;
        }

        @Override
        public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        }

        @Override
        public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        }
}
